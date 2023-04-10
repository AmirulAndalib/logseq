(ns frontend.components.ai
  (:require [rum.core :as rum]
            [frontend.ui :as ui]
            [frontend.state :as state]
            [frontend.mixins :as mixins]
            [frontend.util :as util]
            [goog.object :as gobj]
            [goog.dom :as gdom]
            [frontend.handler.ai :as ai-handler]))

(rum/defc input
  [q]
  (let [on-change-fn (fn [e]
                       (let [value (util/evalue e)
                             e-type (gobj/getValueByKeys e "type")]
                         (state/set-state! [:ui/ai-dialog :q] value)))]
    [:div.flex.w-full.relative
     [:input.form-input.block.sm:text-sm.sm:leading-5.my-2.border-none.outline-none.shadow-none.focus:shadow-none
      {:auto-focus true
       :placeholder "What do you want to know?"
       :aria-label "What do you want to know?"
       :value q
       :on-change on-change-fn
       :on-key-down   (fn [^js e]
                        (when (= (gobj/get e "key") "Enter")
                          (ai-handler/ask! q {})))}]]))

(rum/defc dialog-inner < rum/static
  (mixins/event-mixin
   (fn [state]
     (mixins/hide-when-esc-or-outside
      state
      :node (gdom/getElement "ai-dialog")
      :on-hide ai-handler/close-dialog!)))
  [{:keys [q]}]
  [:div#ai-dialog.flex.flex-1.flex-row.absolute.bottom-4.shadow-lg.p-4.faster-fade-in.items-center
   (input q)])

(rum/defc dialog < rum/reactive
  []
  (let [{:keys [active?] :as opt} (state/sub :ui/ai-dialog)]
    (when active?
      (dialog-inner opt))))