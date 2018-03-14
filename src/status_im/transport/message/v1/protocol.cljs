(ns status-im.transport.message.v1.protocol
  (:require [status-im.transport.utils :as transport.utils]
            [status-im.transport.message-cache :as message-cache]
            [status-im.transport.db :as transport.db]
            [status-im.transport.core :as transport]
            [status-im.transport.message.core :as message]
            [status-im.transport.utils :as transport.utils]))

(def ttl 10) ;; ttl of 10 sec

(defn init-chat [chat-id {:keys [db] :as cofx}]
  {:db (assoc-in db [:transport/chats chat-id] (transport.db/create-chat (transport.utils/get-topic chat-id)))})

(defn is-new? [message-id]
  (when-not (message-cache/exists? message-id)
    (message-cache/add! message-id)))

(defn requires-ack [message-id chat-id {:keys [db] :as cofx}]
  {:db (update-in db [:transport/chats chat-id :pending-ack] conj message-id)})

(defn ack [message-id chat-id {:keys [db] :as cofx}]
  {:db (update-in db [:transport/chats chat-id :ack] conj message-id)})

(defn send [{:keys [payload chat-id success-event]} {:keys [db] :as cofx}]
  ;; we assume that the chat contains the contact public-key
  (let [{:keys [current-public-key web3]} db
        {:keys [sym-key-id topic]} (get-in db [:transport/chats chat-id])]
    {:shh/post {:web3    web3
                :success-event success-event
                :message {:sig current-public-key
                          :symKeyID sym-key-id
                          :ttl ttl
                          :powTarget 0.001
                          :powTime 1
                          :payload  payload
                          :topic topic}}}))

(defn send-with-pubkey [{:keys [payload chat-id success-event]} {:keys [db] :as cofx}]
  (let [{:keys [current-public-key web3]} db
        {:keys [topic]} (get-in db [:transport/chats chat-id])]
    {:shh/post {:web3    web3
                :success-event success-event
                :message {:sig current-public-key
                          :pubKey chat-id
                          :ttl ttl
                          :powTarget 0.001
                          :powTime 1
                          :payload  payload
                          :topic topic}}}))

(defrecord Ack [message-ids]
  message/StatusMessage
  (send [this cofx chat-id])
  (receive [this db chat-id sig]))

(defrecord Seen [message-ids]
  message/StatusMessage
  (send [this cofx chat-id])
  (receive [this cofx chat-id sig]))

(defrecord Message [content content-type message-type to-clock-value timestamp]
  message/StatusMessage
  (send [this chat-id cofx]
    (send {:chat-id       chat-id
           :payload       this
           :success-event [:update-message-status
                           chat-id
                           (transport.utils/message-id this)
                           (get-in cofx [:db :current-public-key])
                           :sent]}
          cofx))
  (receive [this chat-id signature cofx]
    {:dispatch [:chat-received-message/add (assoc (into {} this)
                                                  :message-id (transport.utils/message-id this)
                                                  :chat-id    chat-id
                                                  :from       signature)]}))

(defrecord MessagesSeen [message-ids]
  message/StatusMessage
  (send [this chat-id cofx]
    (send {:chat-id chat-id
           :payload this}
          cofx))
  (receive [this chat-id signature cofx]
    (message/receive-seen chat-id signature (:message-ids this) cofx)))
