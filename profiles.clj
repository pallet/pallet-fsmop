{:dev {:dependencies [[ch.qos.logback/logback-classic "1.0.9"]]
       :plugins [[codox/codox.leiningen "0.6.4"]
                 [lein-marginalia "0.7.1"]]
       :warn-on-reflection true}
 :clojure-1.5 {:dependencies [[org.clojure/clojure "1.5.1"]]}
 :doc {:dependencies [[com.palletops/pallet-codox "0.1.0"]]
       :codox {:writer codox-md.writer/write-docs
               :output-dir "doc/0.3"
               :src-dir-uri "https://github.com/pallet/pallet-fsmop/blob/develop"
               :src-linenum-anchor-prefix "L"}
       :aliases {"marg" ["marg" "-d" "doc/0.3/annotated"]
                 "codox" ["doc"]
                 "doc" ["do" "codox," "marg"]}}
 :release
 {:plugins [[lein-set-version "0.2.1"]]
  :set-version
  {:updates [{:path "README.md"
              :no-snapshot true
              :search-regex #"pallet-fsmop \"\d+\.\d+\.\d+\""}]}}}
