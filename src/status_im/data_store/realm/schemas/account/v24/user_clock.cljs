(ns status-im.data-store.realm.schemas.account.v24.user-clock
  (:require [taoensso.timbre :as log]))

(def schema {:name       :user-clock
             :primaryKey :public-key
             :properties {:public-key      "string"
                          :clock {:type    "integer"
                                  :default 0}}})

(defn migration [_ _]
  (log/debug "migrating user-clock schema"))
