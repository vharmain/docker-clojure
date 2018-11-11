(ns docker-clojure.core
  (:require
   [clojure.java.shell :refer [sh]]
   [clojure.math.combinatorics :as combo]
   [clojure.string :as str]
   [docker-clojure.dockerfile :as df]))

(def base-images
  #{"openjdk:8" "openjdk:11"})

(def distros
  #{"debian" "alpine"})

(def build-tools
  {"lein"       "2.8.1"
   "boot"       "2.8.1"
   "tools-deps" "1.9.0.397"})

(def exclusions ; don't build these for whatever reason(s)
  #{{:base-image "openjdk:11"
     :distro     "alpine"}})

(def maintainers
  {:paul "Paul Lam <paul@quantisan.com>"
   :wes  "Wes Morgan <wesmorgan@icloud.com>"
   :dlg  "Kirill Chernyshov <delaguardo@gmail.com>"})

(defn maintainer [{:keys [distro build-tool]}]
  (cond
    (and (= distro "debian") (= build-tool "lein"))
    (:paul maintainers)

    (and (= distro "debian") (= build-tool "boot"))
    (:wes maintainers)

    (= build-tool "tools-deps")
    (:dlg maintainers)

    (= distro "alpine")
    (:wes maintainers)))

(defn exclude? [exclusions variant]
  (some (fn [exclusion]
          (every? (fn [[k v]]
                    (= v (get variant k)))
                  exclusion))
        exclusions))

(defn base-image->tag-component [base-image]
  (str/replace base-image ":" "-"))

(defn docker-tag [{:keys [base-image distro build-tool]}]
  (let [build-tool-version (get build-tools build-tool)
        jdk-label (if (= "openjdk:8" base-image)
                    nil
                    (base-image->tag-component base-image))
        distro-label (if (= "debian" distro) nil distro)]
    (str/join "-" (remove nil? [jdk-label build-tool build-tool-version
                                distro-label]))))

(defn variant-map [[base-image distro build-tool]]
  (let [base {:base-image base-image
              :distro     distro
              :build-tool build-tool}]
    (-> base
        (assoc :maintainer (maintainer base))
        (assoc :docker-tag (docker-tag base))
        (assoc :build-tool-version (get build-tools (:build-tool base))))))

(defn build-image [{:keys [docker-tag dockerfile] :as variant}]
  (let [image-tag (str "clojure:" docker-tag)
        build-cmd ["docker" "build" "-t" image-tag "-f"
                   dockerfile "empty"]]
    (df/write-file dockerfile variant)
    (apply println "Running" build-cmd)
    (let [{:keys [out err exit]} (apply sh build-cmd)]
      (if (zero? exit)
        (println "Succeeded")
        (do
          (println "ERROR:" err)
          (print out)))))
  (println))

(defn image-variants [base-images distros build-tools]
  (->> (combo/cartesian-product base-images distros build-tools)
       (map variant-map)
       set))

(defn build-images [variants]
  (println "Building images")
  (doseq [variant variants]
    (when-not (exclude? exclusions variant)
      (build-image variant))))

(defn generate-dockerfile! [variant]
  (let [filename (df/filename variant)]
    (println "Generating" filename)
    (df/write-file filename variant)
    (assoc variant :dockerfile filename)))

(defn generate-dockerfiles! []
  (for [variant (image-variants base-images distros (keys build-tools))
        :when (not (exclude? exclusions variant))]
    (generate-dockerfile! variant)))

(defn -main [& args]
  (case (first args)
    "clean" (df/clean-all)
    "dockerfiles" (dorun (generate-dockerfiles!))
    (build-images (generate-dockerfiles!)))
  (System/exit 0))

