
;; Service that yields estimates of the irrational number pi, via server-sent events
(ns server-sent-events.service
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.sse :as sse]
[http.async.client :as http0]
;[clojure-kubernetes-client.core :as core]
;;            [clojure-kubernetes-client.api.core-v1 ]

[io.pedestal.http.route :as route]
            [io.pedestal.http.route.definition :refer [defroutes]]
            [io.pedestal.log]
            [cheshire.core :refer :all]
            [ring.util.response :as ring-resp]
            [clj-http.client :as client]
            [clojure.java.io :as io]
            [clojure.core.async :as async]
            )
  (:use
       java-time))

;(def ips '("10.42.0.81" "10.42.0.80" "10.42.0.79"))
(def live-ips
  (let [env-result (or (System/getenv "PODIPS") "")]
  (atom  (re-seq #"[^,]+" env-result)) ; TEST THIS
  ))
;it was (def live-ips (atom '()))


; The following returns a lazy list representing iterable sums that estimate pi
; according to the Leibniz series for increasing amounts of terms in the series.
                                        ; Sample usage: (take 100 leibniz-reductions)
(defn extract-num [s]
  ( let [re  (apply str (filter #(#{\0,\1,\2,\3,\4,\5,\6,\7,\8,\9,\.,\-} %) s )) ] (if (clojure.string/blank? re)
                                                                                     0 (Float/parseFloat re))))
; We will need a larger number to represent it for now (10^n), and f

(defn edn-events
  "events stream"
  [dump-url]
  (let [lines (-> dump-url
                 (client/get {:as :stream})
                 :body
                 io/reader 
                 line-seq) ];;A lazy seq of each line in the stream.
    (async/to-chan lines)))

(defn send-result
  [event-ch]
  (def sum (atom 0))

  (add-watch sum :watcher
             (fn [key atom old-state new-state]
               (async/put! event-ch (str new-state))
               ))
  (let [[ip1 ip2 ip3] @live-ips str1 (str "http://" ip1 ":8080/leibniz?start=" 0 "&step=" 3)   str2 (str "http://" ip2 ":8080/leibniz?start=" 1 "&step=" 3)  str3 (str "http://" ip3 ":8080/leibniz?start=" 2 "&step=" 3)  events1 (edn-events str1)  events2 (edn-events str2) events3 (edn-events str3)]

     (async/go-loop []
       (when-let [event (async/<! events1)]
         (swap! sum (partial + (extract-num event)))
      (io.pedestal.log/debug :msg (str event))
      (recur)))
     
         (async/go-loop []
       (when-let [event2 (async/<! events2)]
         (swap! sum (partial + (extract-num event2)))
 (io.pedestal.log/debug :msg (str event2))
      (recur)))

           (async/go-loop []
       (when-let [event3 (async/<! events3)]
         (swap! sum (partial + (extract-num event3)))
 (io.pedestal.log/debug :msg (str event3))
      (recur)))
     
  ))



(defn sse-leibniz-stream-ready
  "Start to send estimates to the client according to the Leibniz series"
  [event-ch ctx]
    (send-result event-ch)     
  )
(def wait-seconds 30)
;(defn ahora [] (int (/ (.getTime (java.util.Date.)) 1000)))
;(defn ahora [] (quot (System/currentTimeMillis) 1000))
(defn new-stopwatch [my-time-now-fn] (atom {:started false  :timer-end nil :ready false :time-now-fn  my-time-now-fn}))
(def statefulset-apply-stopwatch (new-stopwatch instant)) ; could have been (def stopwatch (new-stopwatch ahora))... this works because of "min" below being polymorphic via "programming to abstractions" -- or rather, "min" working for both ints, longs, java.util.Dates, and clj-java8 date wrapper obejcts
(def headless-service-apply-stopwatch (new-stopwatch instant))

(defn bang! [stopwatch-x] (swap! stopwatch-x assoc :started true :timer-end (plus ((:time-now-fn @stopwatch-x)) (seconds wait-seconds))) )
(defn expiredp [stopwatch-x]  (if (:ready @stopwatch-x) true  (let [prueba (= (min ((:time-now-fn @stopwatch-x)) (:timer-end @stopwatch-x) ) (:timer-end @stopwatch-x) )] (do (if prueba (swap! stopwatch-x assoc :ready true)) prueba  )  )  ))

(def yaml1 {"apiVersion" "apps/v1"   "kind" "StatefulSet"  "metadata" {"name" "web"   "namespace" "clj-ctrl"} "spec" {"selector" {"matchLabels" {"app" "clj-pedestal-sse"} }  "serviceName" "clj-pedestal-sse" "replicas" 3 "template" {  "metadata" {"labels" {"app" "clj-pedestal-sse"} }        "spec" {"containers" [{"name" "clj-pedestal-sse" "image" "wclarkmc/clj-pedestal-sse"  "ports" [{"containerPort" 8080 "name" "web"} ]} ]}      }}})
; yaml2 - create svc first?
(def yaml2 {"apiVersion" "v1" "kind" "Service"  "metadata" {"name" "clj-pedestal-sse"   "namespace" "clj-ctrl"  "labels" {"app" "clj-pedestal-sse"}} "spec" { "ports" [{"port" 8080 "name" "web"  } ]  "clusterIP" "None" "selector" {"app" "clj-pedestal-sse"} }})
(defn k3s-apply-statefulset-handler [request]
(with-open [client (http0/create-client)] ; Create client
  (let [resp (http0/POST client "http://127.0.0.1:8001/apis/apps/v1/namespaces/clj-ctrl/statefulsets" :headers {"Content-Type" "application/json"}  :body (generate-string yaml1))]

    (do
(bang! statefulset-apply-stopwatch)
  {:status 200 :body (http0/string (http0/await resp))} )
    )))

(defn k3s-apply-service-handler [request]
  (let [is-ready? (expiredp statefulset-apply-stopwatch)]
                                        ; (if is-ready?
    (if is-ready?
(with-open [client (http0/create-client)] ; Create client
  (let [resp (http0/POST client "http://127.0.0.1:8001/api/v1/namespaces/clj-ctrl/services" :headers {"Content-Type" "application/json"}  :body (generate-string yaml2))]      
  (do
    (bang! headless-service-apply-stopwatch)
    {:status 200 :body (http0/string (http0/await resp))} )))
  {:status 202 :body "Not ready, fool"} ; 202 - so interesting...  https://stackoverflow.com/questions/9794696/how-do-i-choose-a-http-status-code-in-rest-api-for-not-ready-yet-try-again-lat
)))
(defn k3s-get-pods-handler
  [request]
   {:status 200 :body (reduce str (interpose "," @live-ips))}
  )
(defn k3s-set-pods-handler
  [request]
  (let [is-ready? (expiredp headless-service-apply-stopwatch)]
    (if is-ready?

(with-open [client (http0/create-client)] ; Create client
  (let [resp (http0/GET client "http://127.0.0.1:8001/api/v1/namespaces/clj-ctrl/pods")
]
    (do
    (http0/await resp)

(swap! live-ips concat  (vec (map  (fn [x] (get (get x "status") "podIP")  ) (get (parse-string  (http0/string resp)) "items" ) )))

    {:status 200 :body "Ya hecho"}) ) )


{:status 202 :body "Not ready, fool!"}
  )))
(defroutes routes
  [
   [
    ["/leibniz" {:get [::send-result-leibniz
                      (sse/start-event-stream sse-leibniz-stream-ready)]}]
    ["/k3s-get-pods" {:get k3s-get-pods-handler}]
    ["/k3s-set-pods" {:get k3s-set-pods-handler}] ;; make PUT for idempotent request
    ["/k3s-apply-service"  {:get k3s-apply-service-handler}]
    ["/k3s-apply-statefulset" {:get k3s-apply-statefulset-handler}]
    ]]  ) 

(def url-for (route/url-for-routes routes))

(def service {:env :prod
              ::http/routes routes
              ::http/resource-path "/public"
              ::http/type :jetty
              ::http/port 8080
              }
)
