(ns logseq.tasks.db-graph.create-graph
  "This ns provides fns to create a DB graph using EDN. See `init-conn` for
  initializing a DB graph with a datascript connection that syncs to a sqlite DB
  at the given directory. See `create-blocks-tx` for the EDN format to create a
  graph. This ns can likely be used to also update graphs"
  (:require [logseq.db.sqlite.db :as sqlite-db]
            [logseq.db.sqlite.util :as sqlite-util]
            [cljs-bean.core :as bean]
            [logseq.db :as ldb]
            [clojure.string :as string]
            [datascript.core :as d]
            ["fs" :as fs]
            ["path" :as node-path]
            [nbb.classpath :as cp]
            ;; TODO: Move these namespaces to more stable deps/ namespaces
            [frontend.modules.datascript-report.core :as ds-report]
            [frontend.modules.outliner.pipeline-util :as pipeline-util]
            [frontend.handler.common.repo :as repo-common-handler]))

(defn- invoke-hooks
  "Modified copy frontend.modules.outliner.pipeline/invoke-hooks that doesn't
  handle :block/path-refs recalculation"
  [{:keys [db-after] :as tx-report}]
  (let [{:keys [blocks]} (ds-report/get-blocks-and-pages tx-report)
        deleted-block-uuids (set (pipeline-util/filter-deleted-blocks (:tx-data tx-report)))
        upsert-blocks (pipeline-util/build-upsert-blocks blocks deleted-block-uuids db-after)]
    {:blocks upsert-blocks
     :deleted-block-uuids deleted-block-uuids}))

(defn- update-sqlite-db
  "Modified copy of :db-transact-data defmethod in electron.handler"
  [db-name {:keys [blocks deleted-block-uuids]}]
  (when (seq deleted-block-uuids)
    (sqlite-db/delete-blocks! db-name deleted-block-uuids))
  (when (seq blocks)
    (let [blocks' (mapv sqlite-util/ds->sqlite-block blocks)]
      (sqlite-db/upsert-blocks! db-name (bean/->js blocks')))))

(defn- find-on-classpath [rel-path]
  (some (fn [dir]
          (let [f (node-path/join dir rel-path)]
            (when (fs/existsSync f) f)))
        (string/split (cp/get-classpath) #":")))

(defn- setup-init-data
  "Setup initial data same as frontend.handler.repo/create-db"
  [conn]
  ;; App doesn't persist :db/type but it does load it each time
  (d/transact! conn [{:db/id -1 :db/ident :db/type :db/type "db"}])
  (let [config-content (or (some-> (find-on-classpath "templates/config.edn") fs/readFileSync str)
                           (do (println "Setting graph's config to empty since no templates/config.edn was found.")
                               "{}"))]
    (d/transact! conn (repo-common-handler/build-db-initial-data config-content))))

(defn init-conn
  "Create sqlite DB, initialize datascript connection and sync listener and then
  transacts initial data"
  [dir db-name]
  (fs/mkdirSync (node-path/join dir db-name) #js {:recursive true})
  (sqlite-db/open-db! dir db-name)
  ;; Same order as frontend.db.conn/start!
  (let [conn (ldb/start-conn :create-default-pages? false)]
    (d/listen! conn :persist-to-sqlite (fn persist-to-sqlite [tx-report]
                                         (update-sqlite-db db-name (invoke-hooks tx-report))))
    (ldb/create-default-pages! conn)
    (setup-init-data conn)
    conn))

(defn- translate-property-value
  "Translates a property value as needed. A value wrapped in vector indicates a reference type
   e.g. [:page \"some page\"]"
  [val {:keys [page-uuids block-uuids]}]
  (if (vector? val)
    (case (first val)
      :page
      (or (page-uuids (second val))
          (throw (ex-info (str "No uuid for page '" (second val) "'") {:name (second val)})))
      :block
      (or (block-uuids (second val))
          (throw (ex-info (str "No uuid for block '" (second val) "'") {:name (second val)})))
      (throw (ex-info "Invalid property value type. Valid values are :block and :page" {})))
    val))

(defn- ->block-properties-tx [properties {:keys [property-uuids] :as uuid-maps}]
  (->> properties
       (map
        (fn [[prop-name val]]
          [(or (property-uuids prop-name)
               (throw (ex-info "No uuid for property" {:name prop-name})))
            ;; set indicates a :many value
           (if (set? val)
             (set (map #(translate-property-value % uuid-maps) val))
             (translate-property-value val uuid-maps))]))
       (into {})))

(defn- create-uuid-maps
  "Creates maps of unique page names, block contents and property names to their uuids"
  [pages-and-blocks]
  (let [property-uuids (->> pages-and-blocks
                            (map #(-> (:blocks %) vec (conj (:page %))))
                            (mapcat #(->> % (map :properties) (mapcat keys)))
                            set
                            (map #(vector % (random-uuid)))
                            (into {}))
        page-uuids (->> pages-and-blocks
                        (map :page)
                        (map (juxt :block/name :block/uuid))
                        (into {}))
        block-uuids (->> pages-and-blocks
                         (mapcat :blocks)
                         (map (juxt :block/content :block/uuid))
                         (into {}))]
    {:property-uuids property-uuids
     :page-uuids page-uuids
     :block-uuids block-uuids}))

(defn create-blocks-tx
  "Given an EDN map for defining pages, blocks and properties, this creates a
  vector of transactable data for use with d/transact!. The EDN map is basic and
  only supports defining blocks at the top level. The EDN map has the following keys:

   * :pages-and-blocks - This is a vector of maps containing a :page key and optionally a :blocks
     key when defining a page's blocks. More about each key:
     * :page - This is a datascript attribute map e.g. `{:block/name \"foo\"}` .
       :block/name is required and :properties can be passed to define page properties
     * :blocks - This is a vec of datascript attribute maps e.g. `{:block/content \"bar\"}`.
       :block/content is required and :properties can be passed to define block properties
   * :properties - This is a map to configure properties where the keys are property names
     and the values are maps of datascript attributes e.g. `{:block/schema {:type :checkbox}}`

   The :properties for :pages-and-blocks is a map of property names to property
   values.  Multiple property values for a many cardinality property are defined
   as a set. The following property types are supported: :default, :url,
   :checkbox, :number, :page and :block. :checkbox and :number values are written
   as booleans and integers. :page and :block are references that are written as
   vectors e.g. `[:page \"PAGE NAME\"]` and `[:block \"block content\"]`

   Limitations:
   * Page and block properties do not update :block/refs and :block/path-refs yet"
  [{:keys [pages-and-blocks properties]}]
  (let [;; add uuids before tx for refs in :properties
        pages-and-blocks' (mapv (fn [{:keys [page blocks]}]
                                  (cond-> {:page (merge {:block/uuid (random-uuid)} page)}
                                    (seq blocks)
                                    (assoc :blocks (mapv #(merge {:block/uuid (random-uuid)} %) blocks))))
                                pages-and-blocks)
        {:keys [property-uuids] :as uuid-maps} (create-uuid-maps pages-and-blocks')
        page-count (atom 100001)
        new-db-id #(swap! page-count inc)
        created-at (js/Date.now)
        new-properties-tx (mapv (fn [[prop-name uuid]]
                                  {:block/uuid uuid
                                   :block/schema (merge {:type :default}
                                                        (get-in properties [prop-name :block/schema]))
                                   :block/original-name (name prop-name)
                                   :block/name (string/lower-case (name prop-name))
                                   :block/type "property"
                                   :block/created-at created-at
                                   :block/updated-at created-at})
                                property-uuids)
        pages-and-blocks-tx
        (vec
         (mapcat
          (fn [{:keys [page blocks]}]
            (let [page-id (new-db-id)]
              (into
               ;; page tx
               [(merge (dissoc page :properties)
                       {:db/id page-id
                        :block/original-name (string/capitalize (:block/name page))
                        :block/created-at created-at
                        :block/updated-at created-at}
                       (when (seq (:properties page))
                         {:block/properties (->block-properties-tx (:properties page) uuid-maps)}))]
               ;; blocks tx
               (reduce (fn [acc m]
                         (conj acc
                               (merge (dissoc m :properties)
                                      {:db/id (new-db-id)
                                       :block/format :markdown
                                       :block/path-refs [{:db/id page-id}]
                                       :block/page {:db/id page-id}
                                       :block/left {:db/id (or (:db/id (last acc)) page-id)}
                                       :block/parent {:db/id page-id}
                                       :block/created-at created-at
                                       :block/updated-at created-at}
                                      (when (seq (:properties m))
                                        {:block/properties (->block-properties-tx (:properties m) uuid-maps)}))))
                       []
                       blocks))))
          pages-and-blocks'))]
    (into pages-and-blocks-tx new-properties-tx)))