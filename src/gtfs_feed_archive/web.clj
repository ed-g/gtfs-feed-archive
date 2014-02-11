(ns gtfs-feed-archive.web
  (:gen-class)
  (:refer-clojure :exclude [format]) ;; I like cl-format.
  (:require [clj-http.client :as http] ;; docs at https://github.com/dakrone/clj-http
            clojure.set
            [ring.adapter.jetty :as jetty] 
            [compojure.route :as route]
            [compojure.handler :as handler]
            [taoensso.timbre :as timbre :refer (trace debug info warn error fatal spy with-log-level)]
            [gtfs-feed-archive.javadoc-helper :as javadoc-helper]
            [gtfs-feed-archive.cache-persistance :as cache-persistance]
            [gtfs-feed-archive.cache-manager :as cache-manager]
            [gtfs-feed-archive.download-agent :as download-agent]
            [gtfs-feed-archive.config :as config]
            [gtfs-feed-archive.archive-creator :as archive-creator]
            [gtfs-feed-archive.command-line :as command-line])
  (:use compojure.core
        [hiccup.core :only [h html]]
        hiccup.page ;; html5.
        hiccup.form ;; form-to, check-box, ...
        hiccup.def
        hiccup.element
        gtfs-feed-archive.util 
        clojure.test
        clojure-csv.core
        [clojure.tools.cli :only [cli]] ;; Command-line parsing.
        [clojure.pprint :only [pprint]] 
        [clojure.pprint :rename {cl-format format}]))

(javadoc-helper/set-local-documentation-source!)

(defhtml date-selector [year month day]
  (html (text-field {:size 4} :year year) "-"
        (text-field {:size 2} :month month) "-"
        (text-field {:size 2} :day day)))

(defn with-status [status response]
  ;; starting with either a body string, or a response map, generate a
  ;; response with a given HTTP status.
  (if (map? response)
    (assoc response :status status)
    {:status status :body response}))

(defn status-demo []
  ;; to test: curl -v -v http://127.0.0.1:8081/status
  ;; most browsers won't show the message, they disconnect as soon as they recieve the header.
  (with-status 204 "Try back later.")) 

(defn parse-date [arg]
  (let [d (clj-time.format/parse-local (clj-time.format/formatters :date) arg)]
    (.toDate d)))

(defn archive-filename->download-link [filename]
  (str @config/*archive-output-url* "/" filename))

(defhtml archive-target-page [submit which-feeds year month day force-rebuild]
  (binding [archive-creator/*force-rebuild?* (boolean force-rebuild)]
    [:head [:title "GTFS Feed Archive"]]
    [:body
     (when (= which-feeds "all")
       (let [filename (archive-creator/all-feeds-filename)
             url  (archive-filename->download-link filename) ]
         (archive-creator/build-archive-of-all-feeds! filename)
         (await config/*archive-list*) ;; wait until archive is available.
         [:div [:h2 "Archive of all feeds created."]
          [:p "Download is available at " (link-to url filename)] ] ))
     (when (= which-feeds "since-date")
       (try (let [date (parse-date (str year "-" month "-" day))
                  filename (archive-creator/modified-since-filename date)
                  url (archive-filename->download-link filename) ]
              (info "I was asked to build an archive of feeds modified since" date)
              (archive-creator/build-archive-of-feeds-modified-since! date filename)
              (await config/*archive-list*) ;; wait until archive is available.
              [:div [:h2 "Archive of feeds modified since " date " created."]
               [:p "Archive is available at " (link-to url filename)]])
            (catch Exception e nil)))
     [:p  (link-to "archive-creator" "Return here") " to build another archive."]
     (when archive-cr)

     (comment [:p "All generated archives may be found on the "
               (link-to @config/*archive-output-url* "download page")]) ]))

(defhtml archive-generator-page [submit which-feeds year month day]
  [:head [:title "GTFS Feed Archive"]]
  [:body
   [:h2 "Which feeds would you like to include in the archive?"]
   "<!-- "
   [:p "Button Value: " (h submit) ]
   [:p "Which Feeds: " (h (pr-str which-feeds))] 
   [:p "Date: " (map (comp h str) [year "-" month "-" day])]
   "-->"
   (let [all-feeds? (= which-feeds "all")
         year (or year "2013")
         month (or month "01")
         day (or day "15")]
     (form-to [:post "archive-target"] 
              [:p [:label "All Feeds" (radio-button "which-feeds" all-feeds? "all")]]
              [:p [:label "Feeds Modified Since" (radio-button "which-feeds" (not all-feeds?) "since-date")]
               "Date: " (date-selector year month day)]
              [:p [:label "Force archive rebuild" (check-box "force-rebuild" false)]]
              (submit-button {:name "submit"} "Create Archive")))
   [:p "All previously generated archives may be found on the "
             (link-to @config/*archive-output-url* "download page") "."]])

(defhtml variables-demo []
  [:head [:title "Variables Demonstration."]]
  [:body
   [:h1 "Variables Demonstration"]
   [:p "*out*: " (h *out*)]
   [:p "input csv " (h @config/*input-csv-files*)]
   [:p "cache dir " (h @config/*cache-directory*)]
   (let [al (sort @config/*archive-list*)]
     [:p "archive list holds "  (count al) " entries:"
      [:ol 
      (for [a al]
        [:li (h a) ] )
      ]])
   (let [cm (sort-by :feed-name  (map deref @config/*cache-manager*)) ]
     [:p "cache manager holds "  (count cm) " entries:"
      [:ol 
      (for [a cm]
        [:li (h a) ] )
      ]])])

(defhtml update-feeds []
  [:head [:title "Updating GTFS feeds as we speak."]]
  [:body
   [:h2 "Updating GTFS feeds as we speak."]
   [:p "Check "(link-to "/v" "here") " for a progress report." ]]
  (do (future (archive-creator/update-cache!)
              ;; By saving and reloading the cache we purge unnecessary entries.
              ;; FIXME: is there a race-condition here?? maybe another reason to keep
              ;; the cache-manager as a ref rather than an agent.
              (cache-manager/save-cache-manager!)
              (cache-manager/load-cache-manager!))
      nil))

(defroutes app
  ;; TODO: build a download page which returns 302 for downloads which aren't ready yet?
  #_(GET "/" [] (html
               [:h1 "Hello There!"]
               [:p (link-to "f" "Forms demo." )]
               [:p (link-to "g/path-text" "Parameter demo." )]))
  (GET "/update-feeds" [] ;; update feeds
    (update-feeds))
  (POST "/archive-target" [submit which-feeds year month day force-rebuild]
    (archive-target-page submit which-feeds year month day force-rebuild))
  (POST "/archive-creator" [submit which-feeds year month day]
    (archive-generator-page submit which-feeds year month day))
  (GET "/archive-creator" [submit which-feeds year month day]
    (archive-generator-page submit which-feeds year month day))
  (GET "/status" [] (status-demo))
  (GET "/v" []
    (variables-demo))
  (route/not-found (html [:h1 "Page not found"])))

(def app-site (handler/site app))


;; rebound in start/stop-web-server.
(defonce ^:dynamic *web-server* nil)

(defn stop-web-server! []
  (try 
    (when *web-server*
      (info "attempting to stop web server...")
      (.stop *web-server*)
      (def ^:dynamic *web-server* nil))
    (catch Exception e nil)))

;; actually this is a restart operation.
(defn start-web-server!
  ([]
     (start-web-server! @config/*web-server-port*))
  ([port]
     (stop-web-server!)
     (def ^:dynamic *web-server*
       (jetty/run-jetty #'app-site {:port port :join? false}))))

