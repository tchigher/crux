(ns crux.kv.lmdb.java
  "LMDB KV backend for Crux (alternative).

  Requires org.lmdbjava/lmdbjava on the classpath,
  including native dependencies."
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.spec.alpha :as s]
            [crux.byte-utils :as bu]
            [crux.kv :as kv]
            [crux.memory :as mem])
  (:import java.io.Closeable
           org.agrona.ExpandableDirectByteBuffer
           [org.lmdbjava CopyFlags Cursor Dbi DbiFlags DirectBufferProxy Env EnvFlags Env$MapFullException GetOp PutFlags Txn]))

(defrecord LMDBJavaIterator [^Txn tx ^Cursor cursor ^ExpandableDirectByteBuffer eb]
  kv/KvIterator
  (seek [this k]
    (when (.get cursor (mem/ensure-off-heap k eb) GetOp/MDB_SET_RANGE)
      (.key cursor)))
  (next [this]
    (when (.next cursor)
      (.key cursor)))
  (prev [this]
    (when (.prev cursor)
      (.key cursor)))
  (value [this]
    (.val cursor))
  (refresh [this]
    (.renew cursor tx)
    this)

  Closeable
  (close [_]
    (.close cursor)))

(defrecord LMDBJavaSnapshot [^Dbi dbi ^Txn tx]
  kv/KvSnapshot
  (new-iterator [_]
    (->LMDBJavaIterator tx (.openCursor dbi tx) (ExpandableDirectByteBuffer.)))

  (get-value [_ k]
    (.get dbi tx (mem/->off-heap k)))

  Closeable
  (close [_]
    (.close tx)))

(s/def ::options (s/keys :req-un [:crux.kv/db-dir]
                         :opt-un [:crux.kv/sync?]))

(def ^:dynamic ^{:tag 'long} *mapsize-increase-factor* 1)
(def ^:const max-mapsize-increase-factor 32)

(def ^:private default-env-flags [EnvFlags/MDB_WRITEMAP
                                  EnvFlags/MDB_NOTLS
                                  EnvFlags/MDB_NORDAHEAD])

(def ^:private no-sync-env-flags [EnvFlags/MDB_MAPASYNC
                                  EnvFlags/MDB_NOMETASYNC])

(defn- increase-mapsize [^Env env ^long factor]
  (let [new-mapsize (* factor (.mapSize (.info env)))]
    (log/debug "Increasing mapsize to:" new-mapsize)
    (.setMapSize env new-mapsize)))

(defrecord LMDBJavaKv [db-dir ^Env env ^Dbi dbi]
  kv/KvStore
  (open [this {:keys [db-dir sync? crux.kv.lmdb.java/env-flags] :as options}]
    (s/assert ::options options)
    (let [env (.open (Env/create DirectBufferProxy/PROXY_DB)
                     (io/file db-dir)
                     (into-array EnvFlags (cond-> default-env-flags
                                            (not sync?) (concat no-sync-env-flags))))
          ^String db-name nil]
      (try
        (assoc this
               :env env
               :dbi (.openDbi env db-name ^"[Lorg.lmdbjava.DbiFlags;" (make-array DbiFlags 0))
               :db-dir db-dir)
        (catch Throwable t
          (.close env)
          (throw t)))))

  (new-snapshot [_]
    (->LMDBJavaSnapshot dbi (.txnRead env)))

  (store [this kvs]
    (try
      (with-open [tx (.txnWrite env)]
        (let [kb (ExpandableDirectByteBuffer.)
              vb (ExpandableDirectByteBuffer.)]
          (doseq [[k v] kvs]
            (.put dbi tx (mem/ensure-off-heap k kb) (mem/ensure-off-heap v vb) (make-array PutFlags 0)))
          (.commit tx)))
      (catch Env$MapFullException e
        (binding [*mapsize-increase-factor* (* 2 *mapsize-increase-factor*)]
          (when (> *mapsize-increase-factor* max-mapsize-increase-factor)
            (throw (IllegalStateException. "Too large size of keys to store at once.")))
          (increase-mapsize env *mapsize-increase-factor*)
          (kv/store this kvs)))))

  (delete [this ks]
    (try
      (with-open [tx (.txnWrite env)]
        (let [kb (ExpandableDirectByteBuffer.)
              ks (if (bytes? (first ks))
                   (sort bu/bytes-comparator ks)
                   (sort mem/buffer-comparator ks))]
          (doseq [k ks]
            (.delete dbi tx (mem/ensure-off-heap k kb)))
          (.commit tx)))
      (catch Env$MapFullException e
        (binding [*mapsize-increase-factor* (* 2 *mapsize-increase-factor*)]
          (when (> *mapsize-increase-factor* max-mapsize-increase-factor)
            (throw (IllegalStateException. "Too large size of keys to delete at once.")))
          (increase-mapsize env *mapsize-increase-factor*)
          (kv/delete this ks)))))

  (fsync [this]
    (.sync env true))

  (backup [_ dir]
    (let [file (io/file dir)]
      (when (.exists file)
        (throw (IllegalArgumentException. (str "Directory exists: " (.getAbsolutePath file)))))
      (.mkdirs file)
      (.copy env file (make-array CopyFlags 0))) )

  (count-keys [_]
    (with-open [tx (.txnRead env)]
      (.entries (.stat dbi tx))))

  (db-dir [this]
    (str db-dir))

  (kv-name [this]
    (.getName (class this)))

  Closeable
  (close [_]
    (.close env)))