(ns status-im.chat.events
  (:require [clojure.set :as set]
            [re-frame.core :as re-frame]
            [status-im.constants :as constants]
            [status-im.i18n :as i18n]
            [status-im.chat.models :as models]
            [status-im.chat.console :as console]
            [status-im.chat.constants :as chat.constants]
            [status-im.ui.components.list-selection :as list-selection]
            [status-im.ui.screens.navigation :as navigation]
            [status-im.utils.handlers :as handlers]
            [status-im.transport.message.core :as transport]
            [status-im.transport.message.v1.contact :as transport-contact]
            status-im.chat.events.commands
            status-im.chat.events.requests
            status-im.chat.events.send-message
            status-im.chat.events.queue-message
            status-im.chat.events.receive-message
            status-im.chat.events.console
            status-im.chat.events.webview-bridge))

;;;; Effects

(re-frame/reg-fx
  :protocol-send-seen
  (fn [params]
    #_(protocol/send-seen! params)))

(re-frame/reg-fx
  :browse
  (fn [link]
    (list-selection/browse link)))

;;;; Handlers

(handlers/register-handler-db
  :set-chat-ui-props
  [re-frame/trim-v]
  (fn [db [kvs]]
    (models/set-chat-ui-props db kvs)))

(handlers/register-handler-db
  :toggle-chat-ui-props
  [re-frame/trim-v]
  (fn [db [ui-element]]
    (models/toggle-chat-ui-prop db ui-element)))

(handlers/register-handler-db
  :show-message-details
  [re-frame/trim-v]
  (fn [db [details]]
    (models/set-chat-ui-props db {:show-bottom-info? true
                                  :bottom-info       details})))

(def index-messages (partial into {} (map (juxt :message-id identity))))

(handlers/register-handler-fx
  :load-more-messages
  [(re-frame/inject-cofx :get-stored-messages)]
  (fn [{{:keys [current-chat-id] :as db} :db get-stored-messages :get-stored-messages} _]
    (when-not (get-in db [:chats current-chat-id :all-loaded?])
      (let [loaded-count (count (get-in db [:chats current-chat-id :messages]))
            new-messages (index-messages (get-stored-messages current-chat-id loaded-count))]
        {:db (-> db
                 (update-in [:chats current-chat-id :messages] merge new-messages)
                 (update-in [:chats current-chat-id :not-loaded-message-ids] #(apply disj % (keys new-messages)))
                 (assoc-in [:chats current-chat-id :all-loaded?]
                           (> constants/default-number-of-messages (count new-messages))))}))))

(handlers/register-handler-db
  :message-appeared
  [re-frame/trim-v]
  (fn [db [{:keys [chat-id message-id]}]]
    (update-in db [:chats chat-id :messages message-id] assoc :appearing? false)))

(handlers/register-handler-fx
  :update-message-status
  [re-frame/trim-v]
  (fn [{:keys [db]} [chat-id message-id user-id status]]
    (let [msg-path [:chats chat-id :messages message-id]
          new-db   (update-in db (conj msg-path :user-statuses) assoc user-id status)]
      {:db             new-db
       :update-message (-> (get-in new-db msg-path) (select-keys [:message-id :user-statuses]))})))

(defn init-console-chat
  [{:keys [chats] :as db}]
  (if (chats constants/console-chat-id)
    {:db db}
    {:db                (-> db
                            (assoc :current-chat-id constants/console-chat-id)
                            (update :chats assoc constants/console-chat-id console/chat))
     :dispatch          [:add-contacts [console/contact]]
     :save-chat         console/chat
     :save-all-contacts [console/contact]}))

(handlers/register-handler-fx
  :init-console-chat
  (fn [{:keys [db]} _]
    (init-console-chat db)))

(handlers/register-handler-fx
  :initialize-chats
  [(re-frame/inject-cofx :all-stored-chats)
   (re-frame/inject-cofx :inactive-chat-ids)
   (re-frame/inject-cofx :get-stored-messages)
   (re-frame/inject-cofx :stored-unviewed-messages)
   (re-frame/inject-cofx :stored-message-ids)
   (re-frame/inject-cofx :get-stored-unanswered-requests)]
  (fn [{:keys [db
               all-stored-chats
               inactive-chat-ids
               stored-unanswered-requests
               get-stored-messages
               stored-unviewed-messages
               stored-message-ids]} _]
    (let [chat->message-id->request (reduce (fn [acc {:keys [chat-id message-id] :as request}]
                                              (assoc-in acc [chat-id message-id] request))
                                            {}
                                            stored-unanswered-requests)
          chats (reduce (fn [acc {:keys [chat-id] :as chat}]
                          (let [chat-messages (index-messages (get-stored-messages chat-id))]
                            (assoc acc chat-id
                                   (assoc chat
                                          :unviewed-messages (get stored-unviewed-messages chat-id)
                                          :requests (get chat->message-id->request chat-id)
                                          :messages chat-messages
                                          :not-loaded-message-ids (set/difference (get stored-message-ids chat-id)
                                                                                  (-> chat-messages keys set))))))
                        {}
                        all-stored-chats)]
      (-> db
          (assoc :chats chats
                 :deleted-chats inactive-chat-ids)
          init-console-chat
          (update :dispatch-n conj [:load-default-contacts!])))))

(handlers/register-handler-fx
  :browse-link-from-message
  (fn [_ [_ link]]
    {:browse link}))

(defn- persist-seen-messages
  [chat-id unseen-messages-ids {:keys [db]}]
  {:update-messages (map (fn [message-id]
                           (-> (get-in db [:chats chat-id :messages message-id])
                               (select-keys [:message-id :user-statuses])))
                         unseen-messages-ids)})

(defn- send-messages-seen
  [chat-id {:keys [db] :as cofx}]
  (let [me                  (:current-chat-id db)
        messages-path       [:chats chat-id :messages]
        unseen-messages-ids (into #{}
                                  (comp (filter (fn [[_ {:keys [user-statuses outgoing]}]]
                                                  (and (not outgoing)
                                                       (not= (get user-statuses me) :seen))))
                                        (map first))
                                  (get-in db messages-path))]
    (when (seq unseen-messages-ids)
      (handlers/merge-fx cofx
                         {:db (-> (reduce (fn [new-db message-id]
                                            (assoc-in db (into messages-path [message-id :user-statuses me]) :seen))
                                          db
                                          unseen-messages-ids)
                                  (update-in [:chats chat-id :unviewed-messages] set/difference unseen-messages-ids))}
                         (persist-seen-messages chat-id unseen-messages-ids)
                         (transport/send (transport-contact/map->ContactMessagesSeen
                                          {:message-ids unseen-messages-ids})
                                         chat-id)))))

(defn- fire-off-chat-loaded-event
  [chat-id {:keys [db]}]
  (when-let [event (get-in db [:chats chat-id :chat-loaded-event])]
    {:db       (update-in [:chats chat-id] dissoc :chat-loaded-event)
     :dispatch event}))

(defn preload-chat-data
  "Takes coeffects map and chat-id, returns effects necessary when navigating to chat"
  [{:keys [db] :as cofx} chat-id]
  (handlers/merge-fx cofx
                     {:db (-> (assoc db :current-chat-id chat-id)
                              (models/set-chat-ui-props {:validation-messages nil}))}
                     (fire-off-chat-loaded-event chat-id)
                     (send-messages-seen chat-id)))

(handlers/register-handler-fx
  :add-chat-loaded-event
  [(re-frame/inject-cofx :get-stored-chat) re-frame/trim-v]
  (fn [{:keys [db] :as cofx} [chat-id event]]
    (if (get (:chats db) chat-id)
      {:db (assoc-in db [:chats chat-id :chat-loaded-event] event)}
      (-> (models/add-chat chat-id cofx) ; chat not created yet, we have to create it
          (assoc-in [:db :chats chat-id :chat-loaded-event] event)))))

;; TODO(janherich): remove this unnecessary event in the future (only model function `add-chat` will stay)
(handlers/register-handler-fx
  :add-chat
  [(re-frame/inject-cofx :get-stored-chat) re-frame/trim-v]
  (fn [cofx [chat-id chat-props]]
    (models/add-chat chat-id chat-props cofx)))

(defn navigate-to-chat
  "Takes coeffects map and chat-id, returns effects necessary for navigation and preloading data"
  ([cofx chat-id]
   (navigate-to-chat cofx chat-id false))
  ([cofx chat-id navigation-replace?]
   (let [nav-fn (if navigation-replace?
                  #(navigation/replace-view % :chat)
                  #(navigation/navigate-to % :chat))]
     (-> (preload-chat-data cofx chat-id)
         (update :db nav-fn)))))

(handlers/register-handler-fx
  :navigate-to-chat
  [re-frame/trim-v]
  (fn [cofx [chat-id {:keys [navigation-replace?]}]]
    (navigate-to-chat cofx chat-id navigation-replace?)))

(handlers/register-handler-fx
  :start-chat
  [(re-frame/inject-cofx :get-stored-chat) re-frame/trim-v]
  (fn [{:keys [db] :as cofx} [contact-id {:keys [navigation-replace?]}]]
    (when (not= (:current-public-key db) contact-id)        ; don't allow to open chat with yourself
      (if (get (:chats db) contact-id)
        (navigate-to-chat cofx contact-id navigation-replace?) ; existing chat, just preload and displey
        (let [add-chat-fx (models/add-chat contact-id cofx)] ; new chat, create before preload & display
          (merge add-chat-fx
                 (navigate-to-chat (assoc cofx :db (:db add-chat-fx))
                                   contact-id
                                   navigation-replace?)))))))

;; TODO(janherich): remove this unnecessary event in the future (only model function `update-chat` will stay)
(handlers/register-handler-fx
  :update-chat!
  [re-frame/trim-v]
  (fn [cofx [chat]]
    (models/update-chat chat cofx)))

(handlers/register-handler-fx
  :delete-chat
  [re-frame/trim-v]
  (fn [cofx [chat-id]]
    (-> (models/remove-chat chat-id cofx)
        (update :db navigation/replace-view :home))))

(handlers/register-handler-fx
  :delete-chat?
  [re-frame/trim-v]
  (fn [_ [chat-id group?]]
    {:show-confirmation {:title               (i18n/label :t/delete-confirmation)
                         :content             (i18n/label (if group? :t/delete-group-chat-confirmation :t/delete-chat-confirmation))
                         :confirm-button-text (i18n/label :t/delete)
                         :on-accept           #(re-frame/dispatch [:delete-chat chat-id])}}))
