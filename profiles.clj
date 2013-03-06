{:dev {:dependencies [[ch.qos.logback/logback-classic "1.0.9"]]
       :warn-on-reflection true}
 :clojure-1.5 {:dependencies [[org.clojure/clojure "1.5.0"]]}
 :codox {:codox {:writer codox-md.writer/write-docs
                 :output-dir "doc/0.1"}
         :dependencies [[codox-md "0.1.0"]
                        [codox/codox.core "0.6.1"]]
         :pedantic :warn}
 :marginalia {:pedantic :warn
              :dir "doc/0.1/annotated"}
 :release
 {:plugins [[lein-set-version "0.2.1"]]
  :set-version
  {:updates [{:path "README.md"
              :no-snapshot true
              :search-regex #"pallet-fsmop \"\d+\.\d+\.\d+\""}]}}}
