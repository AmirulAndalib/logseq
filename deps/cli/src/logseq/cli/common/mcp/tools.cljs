(ns logseq.cli.common.mcp.tools
  "MCP tool related fns shared between CLI and frontend"
  (:require [clojure.string :as string]
            [datascript.core :as d]
            [logseq.common.util :as common-util]
            [logseq.common.util.date-time :as date-time-util]
            [logseq.db :as ldb]
            [logseq.db.frontend.class :as db-class]
            [logseq.db.frontend.entity-util :as entity-util]
            [logseq.db.frontend.property :as db-property]
            [logseq.db.frontend.property.type :as db-property-type]
            [logseq.db.sqlite.export :as sqlite-export]
            [logseq.outliner.tree :as otree]
            [logseq.outliner.validate :as outliner-validate]
            [malli.core :as m]
            [malli.error :as me]))

(defn- ensure-db-graph
  [db]
  (when-not (ldb/db-based-graph? db)
    (throw (ex-info "This tool must be called on a DB graph" {}))))

(defn list-properties
  "Main fn for ListProperties tool"
  [db]
  (ensure-db-graph db)
  (->> (d/datoms db :avet :block/tags :logseq.class/Property)
       (map #(d/entity db (:e %)))
       #_((fn [x] (prn :prop-keys (distinct (mapcat keys x))) x))
       (map (fn [e]
              (cond-> (into {} e)
                true
                (dissoc e :block/tags :block/order :block/refs :block/name :db/index
                        :logseq.property.embedding/hnsw-label-updated-at :logseq.property/default-value)
                true
                (update :block/uuid str)
                (:logseq.property/classes e)
                (update :logseq.property/classes #(mapv :db/ident %))
                (:logseq.property/description e)
                (update :logseq.property/description db-property/property-value-content))))))

(defn list-tags
  "Main fn for ListTags tool"
  [db]
  (ensure-db-graph db)
  (->> (d/datoms db :avet :block/tags :logseq.class/Tag)
       (map #(d/entity db (:e %)))
       (map (fn [e]
              (cond-> (into {} e)
                true
                (dissoc e :block/tags :block/order :block/refs :block/name
                        :logseq.property.embedding/hnsw-label-updated-at)
                true
                (update :block/uuid str)
                (:logseq.property.class/extends e)
                (update :logseq.property.class/extends #(mapv :db/ident %))
                (:logseq.property.class/properties e)
                (update :logseq.property.class/properties #(mapv :db/ident %))
                (:logseq.property.view/type e)
                (assoc :logseq.property.view/type (:db/ident (:logseq.property.view/type e)))
                (:logseq.property/description e)
                (update :logseq.property/description db-property/property-value-content))))))

(defn- get-page-blocks
  [db page-id]
  (let [blocks (ldb/get-page-blocks db page-id)]
      ;; Use repo stub since this is a DB only tool
    (->> (otree/blocks->vec-tree "logseq_db_repo_stub" db blocks page-id)
         (map #(update % :block/uuid str)))))

(defn ^:api remove-hidden-properties
  "Given an entity map, remove properties that shouldn't be returned in api calls"
  [m]
  (->> (remove (fn [[k _v]]
                 (or (= "block.temp" (namespace k))
                     (contains? #{:logseq.property.embedding/hnsw-label-updated-at} k))) m)
       (into {})))

(defn get-page-data
  "Get page data for GetPage tool including the page's entity and its blocks"
  [db page-name-or-uuid]
  (ensure-db-graph db)
  (when-let [page (ldb/get-page db page-name-or-uuid)]
    {:entity (-> (remove-hidden-properties page)
                 (dissoc :block/tags :block/refs)
                 (update :block/uuid str))
     :blocks (map #(-> %
                       remove-hidden-properties
                       ;; remove unused and untranslated attrs
                       (dissoc :block/children :block/page))
                  (get-page-blocks db (:db/id page)))}))

(defn list-pages
  "Main fn for ListPages tool"
  [db]
  (ensure-db-graph db)
  (->> (d/datoms db :avet :block/name)
       (map #(d/entity db (:e %)))
       (remove entity-util/hidden?)
       (map #(-> %
                 ;; Until there are options to limit pages, return minimal info to avoid
                 ;; exceeding max payload size
                 (select-keys [:block/uuid :block/title :block/created-at :block/updated-at])
                 (update :block/uuid str)))))

;; upsert-nodes tool
;; =================
(defn- import-edn-data
  [conn export-map]
  (let [{:keys [init-tx block-props-tx misc-tx error] :as _txs}
        (sqlite-export/build-import export-map @conn {})]
    ;; (cljs.pprint/pprint _txs)
    (when error
      (throw (ex-info (str "Error while building import data: " error) {})))
    (let [tx-meta {::sqlite-export/imported-data? true
                   :import-db? true}]
      (ldb/transact! conn (vec (concat init-tx block-props-tx misc-tx)) tx-meta))))

(defn- get-ident [idents title]
  (or (get idents title)
      (throw (ex-info (str "No ident found for " (pr-str title)) {}))))

(defn- ops->pages-and-blocks
  [operations {:keys [class-idents]}]
  (let [blocks-by-page
        (group-by #(get-in % [:data :page-id])
                  (filter #(= "block" (:entityType %)) operations))
        new-pages (filter #(and (= "page" (:entityType %)) (= "add" (:operation %))) operations)
        pages-and-blocks
        (into (mapv (fn [op]
                      (cond-> {:page (if-let [journal-day (date-time-util/journal-title->int
                                                           (get-in op [:data :title])
                                                           ;; consider user's date-formatter as needed
                                                           (date-time-util/safe-journal-title-formatters nil))]
                                       {:build/journal journal-day}
                                       {:block/title (get-in op [:data :title])})}
                        (some-> (:id op) (get blocks-by-page))
                        (assoc :blocks
                               (mapv #(hash-map :block/title (get-in % [:data :title]))
                                     (get blocks-by-page (:id op))))))
                    new-pages)
              ;; existing pages
              (map (fn [[page-id ops]]
                     {:page {:block/uuid (uuid page-id)}
                      :blocks (mapv (fn [op]
                                      (if (= "add" (:operation op))
                                        (cond-> {:block/title (get-in op [:data :title])}
                                          (get-in op [:data :tags])
                                          (assoc :build/tags (mapv #(get-ident class-idents %) (get-in op [:data :tags]))))
                                        ;; edit
                                        (cond-> {:block/uuid (uuid (:id op))}
                                          (get-in op [:data :title])
                                          (assoc :block/title (get-in op [:data :title])))))
                                    ops)})
                   (apply dissoc blocks-by-page (map :id new-pages))))]
    pages-and-blocks))

(defn- ops->classes
  [operations {:keys [property-idents class-idents existing-idents]}]
  (let [new-classes (filter #(and (= "tag" (:entityType %)) (= "add" (:operation %))) operations)
        classes (merge
                 (into {} (keep (fn [[k v]]
                                  ;; Removing existing until edits are supported
                                  (when-not (existing-idents v) [v {:block/title k}]))
                                class-idents))
                 (->> new-classes
                      (map (fn [{:keys [data] :as op}]
                             (let [title (get-in op [:data :title])
                                   class-m (cond-> {:block/title title}
                                             (:class-extends data)
                                             (assoc :build/class-extends (mapv #(get-ident class-idents %) (:class-extends data)))
                                             (:class-properties data)
                                             (assoc :build/class-properties (mapv #(get-ident property-idents %) (:class-properties data))))]
                               [(get-ident class-idents title) class-m])))
                      (into {})))]
    classes))

(defn- ops->properties
  [operations {:keys [property-idents class-idents existing-idents]}]
  (let [new-properties (filter #(and (= "property" (:entityType %)) (= "add" (:operation %))) operations)
        properties
        (merge
         (into {} (map (fn [[k v]]
                         ;; Removing existing until edits are supported
                         (when-not (existing-idents v) [v {:block/title k}]))
                       property-idents))
         (->> new-properties
              (map (fn [{:keys [data] :as op}]
                     (let [title (get-in op [:data :title])
                           prop-m (cond-> {:block/title title}
                                    (some->> (:property-type data) keyword (contains? (set db-property-type/user-built-in-property-types)))
                                    (assoc :logseq.property/type (keyword (:property-type data)))
                                    (= "many" (:property-cardinality data))
                                    (assoc :db/cardinality :db.cardinality/many)
                                    (:property-classes data)
                                    (assoc :build/property-classes
                                           (mapv #(get-ident class-idents %) (:property-classes data))
                                           :logseq.property/type :node))]
                       [(get-ident property-idents title) prop-m])))
              (into {})))]
    properties))

(defn- operations->idents
  "Creates property and class idents from all uses of them in operations"
  [db operations]
  (let [existing-idents (atom #{})
        property-idents
        (->> (filter #(and (= "property" (:entityType %)) (= "add" (:operation %)))
                     operations)
             (map #(get-in % [:data :title]))
             (into (mapcat #(get-in % [:data :class-properties])
                           (filter #(and (= "tag" (:entityType %)) (= "add" (:operation %)))
                                   operations)))
             distinct
             (map #(vector % (if (common-util/uuid-string? %)
                               (let [ent (d/entity db [:block/uuid (uuid %)])
                                     ident (:db/ident ent)]
                                 (when-not (entity-util/property? ent)
                                   (throw (ex-info (str (pr-str (:block/title ent))
                                                        " is not a property and can't be used as one")
                                                   {})))
                                 (swap! existing-idents conj ident)
                                 ident)
                               (db-property/create-user-property-ident-from-name %))))
             (into {}))
        class-idents
        (->> (filter #(and (= "tag" (:entityType %)) (= "add" (:operation %))) operations)
             (mapcat (fn [op]
                       (into [(get-in op [:data :title])] (get-in op [:data :class-extends]))))
             (into (mapcat #(get-in % [:data :property-classes])
                           (filter #(and (= "property" (:entityType %)) (= "add" (:operation %)))
                                   operations)))
             (into (mapcat #(get-in % [:data :tags])
                           (filter #(and (= "block" (:entityType %)) (= "add" (:operation %)))
                                   operations)))
             distinct
             (map #(vector % (if (common-util/uuid-string? %)
                               (let [ent (d/entity db [:block/uuid (uuid %)])
                                     ident (:db/ident ent)]
                                 (when-not (entity-util/class? ent)
                                   (throw (ex-info (str (pr-str (:block/title ent))
                                                        " is not a tag and can't be used as one")
                                                   {})))
                                 (swap! existing-idents conj ident)
                                 ident)
                               (db-class/create-user-class-ident-from-name db %))))
             (into {}))]
    {:property-idents property-idents
     :class-idents class-idents
     :existing-idents @existing-idents}))

(def ^:private add-non-block-schema
  [:map
   [:data [:map
           [:title :string]]]])

(def ^:private uuid-string
  [:and :string [:fn {:error/message "Must be a uuid string"} common-util/uuid-string?]])

(def ^:private upsert-nodes-operation-schema
  [:and
   ;; Base schema. Has some overlap with inputSchema
   [:map
    {:closed true}
    [:operation [:enum "add" "edit"]]
    [:entityType [:enum "block" "page" "tag" "property"]]
    [:id {:optional true} [:or :string :nil]]
    [:data [:map
            [:title {:optional true} :string]
            [:page-id {:optional true} :string]
            [:tags {:optional true} [:sequential uuid-string]]
            [:property-type {:optional true} :string]
            [:property-cardinality {:optional true} [:enum "many" "one"]]
            [:property-classes {:optional true} [:sequential :string]]
            [:class-extends {:optional true} [:sequential :string]]
            [:class-properties {:optional true} [:sequential :string]]]]]
   ;; Validate special cases of operation and entityType e.g. required keys and uuid strings
   [:multi {:dispatch (juxt :operation :entityType)}
    [["add" "block"] [:map
                      [:data [:map
                              [:title :string]
                              [:page-id :string]]]]]
    [["add" "page"] add-non-block-schema]
    [["add" "tag"] add-non-block-schema]
    [["add" "property"] add-non-block-schema]
    [["edit" "block"] [:map
                       [:id uuid-string]
                       ;; :tags not supported yet
                       [:data [:map {:closed true}
                               [:page-id uuid-string]
                               [:title :string]]]]]
    ;; other edit's
    [::m/default [:map [:id uuid-string]]]]])

(def ^:private Upsert-nodes-operations-schema
  [:sequential upsert-nodes-operation-schema])

(defn- validate-import-edn
  "Validates everything as coming from add operations, failing fast on first invalid
  node. Will need to adjust add operation assumption when supporting editing pages"
  [{:keys [pages-and-blocks properties classes]}]
  (try
    (doseq [{:block/keys [title] :as m} (vals properties)]
      (outliner-validate/validate-property-title title {:entity-type :property :title title :entity-map m})
      (outliner-validate/validate-page-title-characters title {:entity-type :property :title title :entity-map m})
      (outliner-validate/validate-page-title title {:entity-type :property :title title :entity-map m}))
    (doseq [{:block/keys [title] :as m} (vals classes)]
      (outliner-validate/validate-page-title-characters title {:entity-type :tag :title title :entity-map m})
      (outliner-validate/validate-page-title title {:entity-type :tag :title title :entity-map m}))
    (doseq [{:block/keys [title] :as m} (map :page pages-and-blocks)]
      ;; title is only present for new pages
      (when title
        (outliner-validate/validate-page-title-characters title {:entity-type :page :title title :entity-map m})
        (outliner-validate/validate-page-title title {:entity-type :page :title title :entity-map m})))
    (catch :default e
      (js/console.error e)
      (throw (ex-info (str (string/capitalize (name (get (ex-data e) :entity-type :page)))
                           " " (pr-str (:title (ex-data e))) " is invalid: " (ex-message e))
                      (ex-data e))))))

(defn- summarize-upsert-operations [operations {:keys [dry-run]}]
  (let [counts (reduce (fn [acc op]
                         (let [entity-type (keyword (:entityType op))
                               operation-type (keyword (:operation op))]
                           (update-in acc [operation-type entity-type] (fnil inc 0))))
                       {}
                       operations)]
    (str (if dry-run "Dry run: " "")
         (when (counts :add)
           (str "Added: " (pr-str (counts :add)) "."))
         (when (counts :edit)
           (str " Edited: " (pr-str (counts :edit)) ".")))))

(defn upsert-nodes
  [conn operations* {:keys [dry-run] :as opts}]
  (ensure-db-graph @conn)
  ;; Only support these operations with appropriate outliner validations
  (when (seq (filter #(and (#{"page" "tag" "property"} (:entityType %)) (= "edit" (:operation %))) operations*))
    (throw (ex-info "Editing a page, tag or property isn't supported yet" {})))
  (let [operations
        (->> operations*
             ;; normalize classes as they sometimes have titles in :name
             (map #(if (and (= "tag" (:entityType %)) (= "add" (:operation %)))
                     (assoc-in % [:data :title]
                               (or (get-in % [:data :name]) (get-in % [:data :title])))
                     %)))
        _ (prn :ops operations)
        _ (when-let [errors (m/explain Upsert-nodes-operations-schema operations)]
            (throw (ex-info (str "Tool arguments are invalid:\n" (me/humanize errors))
                            {:errors errors})))
        idents (operations->idents @conn operations)
        pages-and-blocks (ops->pages-and-blocks operations idents)
        classes (ops->classes operations idents)
        properties (ops->properties operations idents)
        import-edn
        (cond-> {}
          (seq pages-and-blocks)
          (assoc :pages-and-blocks pages-and-blocks)
          (seq classes)
          (assoc :classes classes)
          (seq properties)
          (assoc :properties properties))]
    (prn :import-edn import-edn)
    (validate-import-edn import-edn)
    (when-not dry-run (import-edn-data conn import-edn))
    (summarize-upsert-operations operations* opts)))