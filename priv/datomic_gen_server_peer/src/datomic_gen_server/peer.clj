(ns datomic_gen_server.peer
  (:gen-class)
  (:require [clojure.core.async :as async :refer [<! >! <!! go close!]]
            [clojure-erlastic.core :refer [port-connection]]
            [clojure.core.match :refer [match]]
            [datomic.api :as datomic]
            [net.phobot.datomic.migrator :refer [run-migrations]]
            [net.phobot.datomic.seed :refer [seed-database]]
  ))

; TODO Maybe want to configure this
; The amount of time to wait after a migration for a db sync to occur.
(def migration-timeout-ms 5000)

(defn- connect [db-url create?]
  (try 
    (datomic/connect db-url)
    (catch clojure.lang.ExceptionInfo e 
      (if (and create? (= :db.error/db-not-found (:db/error (ex-data e))))
        (do (datomic/create-database db-url)
          (datomic/connect db-url))
        (throw e)))))

(defn- read-edn [edn-str]
  (clojure.edn/read-string {:readers *data-readers*} edn-str))

;; This allows us to bind datomic_gen_server.peer/*db* in the edn that is passed in
(declare ^:dynamic *db*)
(defn- q [database edn-str binding-edn-list]
  (if (empty? binding-edn-list)
      (let [result (-> edn-str (datomic/q database) prn-str)]
        result)
    (binding [*db* database]
      (let [result (->> binding-edn-list (map read-edn) (map eval) (apply datomic/q edn-str) prn-str)]
        result))))

(defn- transact [connection edn-str]
  (let [completed-future (datomic/transact connection (read-edn edn-str))]
    @completed-future))

(defn- entity-attributes [attribute-names entity-map]
  (let [attrs (if (= :all attribute-names)
                (keys entity-map)
                attribute-names)
        selected (select-keys entity-map attrs)]
    (select-keys entity-map attrs)))
                
(defn- entity [database edn-str attr-names]
  (->> (read-edn edn-str) (datomic/entity database) (entity-attributes attr-names) prn-str))
    
(defn- serialize-datoms [datom]
  {:a (.a datom) :e (.e datom) :v (.v datom) :tx (.tx datom) :added (.added datom) })

(defn- serialize-transaction-response [transaction-response]
  (let [db-before (transaction-response :db-before)
        before-basis-t (datomic/basis-t db-before)
        db-after (transaction-response :db-after)
        after-basis-t (datomic/basis-t db-after)
        tx-data (transaction-response :tx-data)]
    (prn-str 
      { :db-before {:basis-t before-basis-t}
        :db-after {:basis-t after-basis-t}
        :tx-data (into [] (map serialize-datoms tx-data))
        :tempids (transaction-response :tempids)
      })))
      
(defn- migrate [connection migration-path]
  ;; TODO Figure out a better way to handle logging
  (let [logger-fn (fn [& args] nil)]
    (run-migrations connection migration-path logger-fn)
    ; run-migrations calls doseq, which returns nil, so migrate does not supply a db-after
    {:db-after (deref (datomic/sync connection) migration-timeout-ms nil)}))

(defn- seed [db-url connection migration-path seed-data-resource-path]
  ;; TODO Figure out a better way to handle logging
  (let [logger-fn (fn [& args] nil)
        completed-future (seed-database connection migration-path seed-data-resource-path logger-fn)]
    @completed-future))
  
; Returns the result along with the state of the database, or nil if shut down.
; Results are vectors starting with :ok or :error so that they go back to Elixir
; as the corresponding tuples.
(defn- process-message [message database connection db-url]
  (try
    (match message
      ; IMPORTANT: RETURN MESSAGE ID IF IT IS AVAILABLE
      [:q id edn binding-edn] {:db database :result [:ok id (q database edn binding-edn)]}
      [:entity id edn attr-names] {:db database :result [:ok id (entity database edn attr-names)]}
      [:transact id edn] 
          (let [result (transact connection edn)]
            {:db (result :db-after) :result [:ok id (serialize-transaction-response result)]})
      [:migrate id migration-path] 
          (let [result (migrate connection migration-path)]
            {:db (result :db-after) :result [:ok id :migrated]})
      [:seed id migration-path seed-data-resource-path] 
          (let [result (seed db-url connection migration-path seed-data-resource-path)]
            {:db (result :db-after) :result [:ok id (serialize-transaction-response result)]})
      [:ping] {:db database :result [:ok :ping]}
      [:stop] (do (datomic/shutdown false) nil) ; For testing from Clojure; does not release Clojure resources
      [:exit] (do (datomic/shutdown true) nil)
      nil (do (datomic/shutdown true) nil)) ; Handle close of STDIN - parent is gone
    (catch Exception e 
      (let [response {:db database :result [:error message e]}]
        response))))

(defn- exit-loop [in out] 
  (do
    (close! out) 
    (close! in) 
    :default))
  
(defn start-server 
  ([db-url in out] (start-server db-url in out false))
  ([db-url in out create?]
    (let [connection (connect db-url create?)]
      (<!! (go 
        (loop [database (datomic/db connection)]
          (let [message (<! in)
                result (process-message message database connection db-url)]
            (if (some? result)
              (do
                (>! out (result :result))
                (recur (result :db)))
              (exit-loop in out))))))))) ; exit if we get a nil back from process-message.

(defn -main [& args]
  (cond
    (empty? args) (System/exit 1)
    (> (count args) 2) (System/exit 1)
    :else 
      (let [ port-config {:convention :elixir :str-detect :all}
             create-arg (second args)
             create? (and (some? create-arg) (.equalsIgnoreCase create-arg "true"))
             [in out] (clojure-erlastic.core/port-connection port-config)]
        (start-server (first args) in out create?))))
        
