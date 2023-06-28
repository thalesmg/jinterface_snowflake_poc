(ns emqx.core
  (:require
   [clojure.java.io :as io]
   [clojure.tools.cli :as cli]
   [cheshire.core :as json])
  (:import
   [java.util Properties]
   [com.ericsson.otp.erlang OtpNode OtpErlangMap]
   [net.snowflake.ingest.streaming
    SnowflakeStreamingIngestClientFactory
    OpenChannelRequest
    OpenChannelRequest$OnErrorOption])
  (:gen-class))

(defn eprintln
  [& args]
  (binding [*out* *err*]
    (apply println args)))

(defn start-streaming-agent
  []
  ;; see https://github.com/snowflakedb/snowflake-ingest-java/blob/64182caf0af959271f4249e4bef9203e2a1f6d8d/profile_streaming.json.example
  (let [params (json/parse-string (slurp "profile.json"))
        props (Properties.)
        _ (doseq [[^String k ^String v] params]
            (.setProperty props k (str v))
            #_(.. props
                  (.setProperty k v)))
        ;; props (.. props
        ;;           (.putAll params))
        client (.. (SnowflakeStreamingIngestClientFactory/builder "my_client")
                   (setProperties props)
                   (build))
        chan-req (.. (OpenChannelRequest/builder "my_channel")
                     (setDBName "TESTDATABASE")
                     (setSchemaName "PUBLIC")
                     (setTableName "TESTTABLE")
                     (setOnErrorOption OpenChannelRequest$OnErrorOption/CONTINUE)
                     (build))
        chan (. client openChannel chan-req)]
    (agent {:chan chan :count 0})))

(defn emap->map
  [emap]
  (if (instance? OtpErlangMap emap)
    (reduce
     (fn [acc k]
       (let [v (.. emap (get k) (intValue))
             k (-> k (.binaryValue) (String.))]
         (assoc acc k v)))
     {}
     (.keys emap))
    {}))

(defn insert-row
  [{:keys [:chan :count] :as state} row]
  (println "inserting" row "in" chan)
  (let [response (. chan insertRow row (str count))]
    (if (.hasErrors response)
      (eprintln "error" response)
      (println "inserted" row))
    (update state :count inc)))

(defn receive-loop
  [mbox streaming-agent]
  (let [o (.receive mbox)]
    (println "received" o)
    ;; assuming just a map with the row...
    (send streaming-agent insert-row (emap->map o))
    (recur mbox streaming-agent)))

(def cli-options
  [[nil "--node-name NODE_NAME" "Java Node Name"
    :default "jemqx@127.0.0.1"]
   [nil "--cookie COOKIE" "Erlang Cookie"
    :default-fn (fn [_]
                  (slurp (io/file (System/getenv "HOME") ".erlang.cookie")))]
   ["-n" "--mailbox-name MAILBOX_NAME" "Mailbox process name to register"
    :default "streamer"]
   [nil "--emqx-node EMQX_NODE_NAME" "EMQX Node Name"
    :default "emqx@127.0.0.1"]])

(defn -main
  [& args]
  (let [{{:keys [:node-name :cookie :mailbox-name :emqx-node]} :options}
        (cli/parse-opts args cli-options)

        node (OtpNode. node-name cookie)
        mbox (.createMbox node mailbox-name)
        streaming-agent (start-streaming-agent)]
    (println "this node" node)
    (println "pinging emqx node" emqx-node)
    (if (.ping node emqx-node 2000)
      (println "ping successful")
      (do
        (println "ping failed")
        (System/exit 1)))
    (receive-loop mbox streaming-agent)))
