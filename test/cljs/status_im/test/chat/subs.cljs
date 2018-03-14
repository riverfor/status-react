(ns status-im.test.chat.subs
  (:require [cljs.test :refer-macros [deftest is testing]]
            [status-im.chat.subs :as s]))


(deftest test-message-datemark-groups
  (testing "it orders a map of messages by clock-values"
    (let [datemark "Jan 1, 1970, 12:00:00 AM"
          message-1 {:show? true
                     :timestamp 0
                     :user->clock {"a" 0 "b" 1 "c" 0}}
          message-2 {:show? true
                     :timestamp 0
                     :user->clock {"a" 1 "b" 2 "c" 1}}
          message-3 {:show? true
                     :timestamp 0
                     :user->clock {"a" 3 "b" 0 "d" 3}}
          unordered-messages {1 message-1
                              2 message-2
                              3 message-3}
          ordered-messages [[datemark
                             (map #(assoc % :datemark datemark)
                                  [message-3 message-2 message-1])]]
          chat-contact-pks ["a" "b" "c"]]

      (is (= ordered-messages (s/message-datemark-groups unordered-messages chat-contact-pks))))))
