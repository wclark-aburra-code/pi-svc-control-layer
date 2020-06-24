(ns server-sent-events.server
  (:gen-class) ; for -main method in uberjar
  (:require [io.pedestal.http :as server]
            [server-sent-events.service :as service]))

(defonce runnable-service (server/create-server service/service))

(defn run-dev
  [& args]
  (println "\nCreating your [DEV] server...")
  (-> service/service ;; start with production configuration
      (merge {:env :dev
              ::server/join? false
              ::server/routes #(deref #'service/routes)
              ::server/allowed-origins {:creds true :allowed-origins (constantly true)}})
      server/default-interceptors
      server/dev-interceptors
      server/create-server
      server/start))

(defn -main
  [& args]
  (println "\nCreating server...")
  (server/start runnable-service))
