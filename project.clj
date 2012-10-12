(defproject pallet-fsmop "0.1.3-SNAPSHOT"
  :description "FSM composition"
  :url "https://github.com/pallet/pallet-fsmop"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.2.1"]
                 [org.clojure/tools.logging "0.2.3"]
                 [pallet-fsm "0.1.1"]
                 [pallet-map-merge "0.1.0"]
                 [pallet-thread "0.1.0"]]
  :profiles {:dev {:dependencies [[ch.qos.logback/logback-classic "1.0.0"]]}})
