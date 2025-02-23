(ns nuestr.consume
  (:require [cljfx.api :as fx]   
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [manifold.stream :as s]
            [nuestr.cache :as cache]
            [nuestr.channels :as channels]
            [nuestr.consume-verify :refer [verify-maybe-persist-event!]]
            [nuestr.domain :as domain]    
            [nuestr.json :as json]
            [nuestr.metadata :as metadata]
            [nuestr.nip05 :as nip05]            
            [nuestr.parse :as parse]    
            [nuestr.relay-conn :as relay-conn]
            [nuestr.status-bar :as status-bar]
            [nuestr.store :as store]
            [nuestr.subscribe :as subscribe]
            [nuestr.tab-relays :as tab-relays]
            [nuestr.timeline :as timeline]
            [nuestr.util :as util])
  (:import (java.util.concurrent ScheduledExecutorService ScheduledFuture TimeUnit)
           (java.time ZonedDateTime Instant)))


;; TODO where do we have stream buffers?
;; TODO add relay info to events
;; TODO we're adding everything right now -- need to respect timeline ux watermarks

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Consuming nostr events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- update-metadata [*state metadata-cache pubkey parsed event]
  ;; Update the cache.
  (metadata/update! metadata-cache pubkey parsed)
  ;; Update keycards.  
  (swap! *state
         (fn [{:keys [identity-metadata] :as curr-state}]
           (if (contains? identity-metadata pubkey)
             (update curr-state :identity-metadata assoc pubkey parsed)
             curr-state)))
  ;; Kick timelines.
  (timeline/dispatch-metadata-update! *state event))


(defn consume-set-metadata-event [_db *state metadata-cache relay-url event-obj]
  (try
    (let [{:keys [pubkey created_at]} event-obj
          {:keys [name about picture nip05]} (json/parse (:content event-obj))
          parsed (domain/->ParsedMetadata name about picture nip05 created_at)]
      (log/trace "metadata: " relay-url (:id event-obj) parsed)
      ;; Handle NIP05 data (if applicable).
      (when nip05
        (let [[nip05-name nip-domain] (str/split nip05 #"@")
              url (str "https://" nip-domain "/.well-known/nostr.json?name=" nip05-name)
              response @(aleph.http/get url)]
          (when-let [body-string (slurp (:body response))]
            (let [body (json/parse body-string)
                  names (:names body)
                  relays (:relays body)]
              (doseq [[n nip05-pubkey] names]
                (let [user-relays (get relays (keyword nip05-pubkey))
                      user-name (str (clojure.core/name n) "@" nip-domain)
                      verified? (= pubkey nip05-pubkey)
                      user (nip05/->User nip05-pubkey user-name user-relays created_at verified?)]
                  (nip05/maybe-add-or-update! user))))
            (timeline/dispatch-metadata-update! *state event-obj))))
      ;; Update cache if this metadata event is newer than any existing one.
      (if-let [existing (metadata/get* metadata-cache pubkey)]
        (when (> created_at (or (:created_at existing) 0))
          (update-metadata *state metadata-cache pubkey parsed event-obj))
        (update-metadata *state metadata-cache pubkey parsed event-obj)))
    (catch Exception e
      (log/warn e "while handling metadata event"))))

(defn consume-contact-list [_db *state ^ScheduledExecutorService executor resubscribe-future-vol relay-url
                            {:keys [id pubkey created_at] :as event-obj}]
  (log/trace "contact list: " relay-url id pubkey created_at)
  (let [{:keys [identities contact-lists]} @*state]
    (when (some #(= % pubkey) (mapv :public-key identities))
      (let [{:keys [created-at]} (get contact-lists pubkey)]
        (when (or (nil? created-at) (> created_at created-at))
          (let [new-contact-list (domain/->ContactList pubkey created_at
                                                       (parse/parse-contacts* event-obj))]
            (swap! *state
              (fn [curr-state]
                (assoc-in curr-state [:contact-lists pubkey] new-contact-list)))))))))

(defn consume-direct-message [db relay-url event-obj]
  #_(log/info "direct message (TODO): " relay-url (:id event-obj))
  )

(defn consume-reaction [db relay-url verified-event]
  #_(log/info "reaction (TODO): " (:id event-obj)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Channel events (NIP-28)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn insert-or-update-channel [db channel]
  (let [id (:id channel)]
    (if (channels/get* id)
      (store/update-channel! db channel)
      (store/insert-channel! db channel))
    (channels/update! id channel)))

(defn consume-channel-create [db relay-url event]
  (try (let [{:keys [pubkey id]} event
             {:keys [name about picture]} (json/parse (:content event))
             channel (domain/->Channel id pubkey name about picture relay-url)]
         #_(log/debugf "Creating channel %s, found on %s." name relay-url)
         (insert-or-update-channel store/db channel))
    (catch Exception e
      (log/debugf "%s while handling channel-create event."
                  (str e)))))

(defn consume-channel-metadata [db relay-url event]
  (try (let [{:keys [pubkey]} event
             e-tag (first (parse/e-tags event))
             [_ id recommended-relay-url] (if (string? e-tag)
                                            ;; Old-style e-tag has only the channel id.
                                            ["e" e-tag relay-url]
                                            ;; NIP-10 e-tag.
                                            e-tag)]
         (if-let [channel (channels/get* id)]
           (when (= (:pubkey channel) pubkey) ; only update if same pubkey (see NIP-28)
             (let [{:keys [name about picture]} (json/parse (:content event))
                   channel (domain/->Channel id pubkey name about picture recommended-relay-url)]
               #_(log/info "Updating channel %s" name)
               (insert-or-update-channel store/db channel)))
           ;; TODO: Maybe we should just create a channel if it doesn't exist yet?
           (log/debugf "Channel '%s' not found, so not updating." id)))
    (catch Exception e
      (log/debugf "%s while handling channel-metadata event."
                  (str e)))))

(defn consume-channel-message [db relay-url verified-event]
  
  )

(defn consume-hide-message [db relay-url verified-event]
  )

(defn consume-mute-user [db relay-url verified-event]
  )


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; General
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- consume-verified-event
  [db *state metadata-cache executor resubscribe-future-vol relay-url subscription-id {:keys [kind] :as verified-event}]
  (case kind
    ;; NIP-01
    0 (consume-set-metadata-event db *state metadata-cache relay-url verified-event)
    1 (let [parts (str/split subscription-id #":")
            prefix (first parts)
            thread? (= prefix "thread")
            profile-thread? (= prefix "pt")
            profile? (= prefix "profile")
            continuation? (= prefix "cont")
            column-or-profile-id (second parts)
            event (assoc verified-event :relays (list relay-url))]
        ;; If the subscription id starts with "thread:" or "pt:", the event is
        ;; caused by timeline/fetch-events-with-ids and it's for a thread view.
        ;; If the subscription id starts with "profile", it's for the events
        ;; belonging to a profile.
        (cond (= prefix "cont")
              (when-let [k (timeline/find-continuation subscription-id)]
                ;; Run the continuation if there is one.
                (k event))
              ;;
              (or thread? profile-thread?)
              (timeline/thread-dispatch! *state
                                         (when thread? (domain/find-column-by-id column-or-profile-id))
                                         (when profile-thread? (domain/find-profile-state-by-id column-or-profile-id))
                                         event)
              ;;
              :else (timeline/dispatch-text-note! *state
                                                  column-or-profile-id ; can be nil
                                                  event
                                                  profile?)))
    2 (tab-relays/maybe-add-relay! (str/trim (:content verified-event)))
    ;; NIP-02
    3 (consume-contact-list db *state executor resubscribe-future-vol relay-url verified-event)
    ;; NIP-04
    4 (consume-direct-message db relay-url verified-event)
    ;; NIP-25
    7 (consume-reaction db relay-url verified-event)
    ;; NIP-28: Channels
    40 (consume-channel-create db relay-url verified-event)
    41 (consume-channel-metadata db relay-url verified-event)
    ; 42 (consume-channel-message db relay-url verified-event)
    ; 43 (consume-hide-message db relay-url verified-event)
    ; 44 (consume-mute-user db relay-url verified-event)
    ;; Everything else
    (log/warn "skipping kind" kind relay-url)))

(defn- consume-event
  [db *state metadata-cache executor resubscribe-future-vol cache relay-url subscription-id {:keys [id kind] :as event-obj} raw-event-tuple]
  (verify-maybe-persist-event! db cache relay-url event-obj raw-event-tuple
    (partial consume-verified-event db *state metadata-cache executor resubscribe-future-vol relay-url subscription-id)
    (fn [_event-obj] ; event we already have - but from new relay
      (log/trace "on-new-relay-seen" relay-url id))
    (fn [event-obj] ; an event we've stored but don't have cached
      ;; For now, we consume all of these assuming that anything not in the cache might
      ;; not have been yet incorporated into ux data.
      (consume-verified-event db *state metadata-cache executor resubscribe-future-vol relay-url subscription-id event-obj))))

(defn- consume-notice
  [relay-url message]
  (log/info "NOTICE (TODO): " relay-url message))

(defn- consume-eose
  [relay-url subscription-id]
  (log/debugf "Unsubscribing from %s in %s." subscription-id relay-url)
  (locking relay-conn/conn-registry
    (let [read-connections @(:read-connections-vol relay-conn/conn-registry)
          connection (get read-connections relay-url)]
      (when connection
        ;; Switch to the next subscription (with a bigger limit) for this relay.
        (if (str/starts-with? subscription-id "flat:")        
          (when-let [current-filter (first (get (:subscriptions @connection) subscription-id))]
            (let [new-filter (subscribe/increase-text-note-filter current-filter)]
              (relay-conn/unsubscribe! connection subscription-id)
              (when-not (= new-filter current-filter)
                (let [[prefix column-id raw-counter] (str/split subscription-id #":")
                      counter (Integer/parseInt raw-counter)
                      new-id (str prefix ":" column-id ":" (inc counter))]
                  (util/schedule! domain/daemon-scheduled-executor
                                  #(relay-conn/maybe-add-subscriptions! relay-url {new-id [new-filter]})
                                  ;; Add the new subscription after waiting a bit.
                                  (* 10 (:limit new-filter)))))))
          (relay-conn/unsubscribe! connection subscription-id))))))
                

(defn- consume*
  [db *state metadata-cache executor resubscribe-future-vol cache [relay-url event-str]]
  (try
    (let [[type-str arg0 arg1] (json/parse event-str)]
      (condp = type-str
        "EVENT" (consume-event db *state metadata-cache executor resubscribe-future-vol cache relay-url arg0 arg1 event-str)
        "NOTICE" (consume-notice relay-url arg0)
        "EOSE" (consume-eose relay-url arg0)
        (log/warn "unknown event type" relay-url type-str)))
    (catch Exception e
      (log/warn "dropping event; bad parse?" relay-url event-str e))))

(def ^:private cache-spec
  "initialCapacity=5000,maximumSize=5000,expireAfterWrite=10m")

(defn start!
  [db *state metadata-cache daemon-scheduled-executor]
  (let [resubscribe-future-vol (volatile! nil)]
    (s/consume
      (partial consume* db *state metadata-cache daemon-scheduled-executor resubscribe-future-vol (cache/build cache-spec))
      (relay-conn/sink-stream))))
