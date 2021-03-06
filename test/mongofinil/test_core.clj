(ns mongofinil.test-core
  (:require [bond.james :as bond]
            [clj-time.core :as time]
            [somnium.congomongo :as congo]
            [mongofinil.core :as core]
            [mongofinil.testing-utils :as utils])
  (:use midje.sweet
        [clojure.core.incubator :only (-?>)])
  (:import org.bson.types.ObjectId))

(defn initializer []
  (congo/add-index! :xs [:a])
  (congo/add-index! :xs [:w])
  (congo/add-index! :xs [:x])
  (congo/add-index! :xs [:dx])
  (congo/add-index! :xs [:y]))

(utils/setup-test-db)
(utils/setup-midje (initializer))

(defn create-hook [row]
  row)

(defn load-hook [row]
  row)

(defn update-hook [row]
  row)

(defn spyable-middleware-fn [x]
  x)

(defn fn-middleware [f]
  (fn [& args]
    (spyable-middleware-fn (apply f args))))

;; This isn't sufficient at deeper nesting levels
(fact "convert-dotmap-to-nested works"
      (let [old_map {:foo {:bar "baz"} :foo_2 "baz_2"}
            new_map {:foo {:bar "new_baz"}}
            new_dotmap {:foo.bar "newest_baz"}]
        ;; Core
        (merge old_map new_map) => {:foo {:bar "new_baz"} :foo_2 "baz_2"}
        ;; Dot-keyword-expansion
        (merge old_map (core/convert-dotmap-to-nested new_dotmap)) => {:foo {:bar "newest_baz"} :foo_2 "baz_2"}))

(core/defmodel :xs
  :fields [;; simple
           {:name :x :findable true}
           {:name :y :findable false}
           {:name :z}
           {:name :w :findable true}

           ;; default
           {:name :dx :default 5}
           {:name :dy :default (fn [b] 6)}
           {:name :dz :default (fn [b] (-> b :x))}

           ;; ordered defaults
           {:name :def1 :default 5}
           {:name :def2 :default (fn [b] (inc (:def1 b)))}
           {:name :def3 :default (fn [b] (inc (:def2 b)))}
           {:name :def4 :default (fn [b] (:x b))}

           ;; transient
           {:name :disx :transient true}

           ;; keyword
           {:name :kw :keyword true}

           ;; strings
           {:name :strs :strings true}

           ;; unique
           {:name :unique1} ;; for congo interop, not a feature

           ;; validation
           {:name :valid-pos :default 5 :validator (fn [row] (when-not (pos? (:valid-pos row)) "Should be positive"))}]
  ;; vars aren't necessary for hooks, but they do make bond/with-spy work in tests.
  :hooks {:create {:pre #'create-hook}
          :load {:post #'load-hook}
          :update {:post #'update-hook}}

  :validations [(fn [row] (when false "failing"))]

  :fn-middleware #'fn-middleware)

(fact "row findable functions are created and work"
  (let [obj1 (create! {:x 1 :y 2 :z 3 :w 4})
        obj2 (create! {:x 2 :y 3 :z 4 :w 5})]

    ;; create worked and added an id
    obj1 =not=> nil
    obj2 =not=> nil
    (contains? obj1 :_id) => true
    (contains? obj2 :_id) => true
    obj1 => (contains {:x 1 :y 2 :z 3 :w 4})
    obj2 => (contains {:x 2 :y 3 :z 4 :w 5})

    ;; success
    (find-one-by-x 1) => (contains obj1)
    (find-one-by-x! 1) => (contains obj1)
    (find-one-by-w 4) => (contains obj1)
    (find-one-by-w! 4) => (contains obj1)
    (find-one-by-x 2) => (contains obj2)
    (find-one-by-x! 2) => (contains obj2)
    (find-one-by-w 5) => (contains obj2)
    (find-one-by-w! 5) => (contains obj2)

    (first (find-by-x 1)) => (contains obj1)
    (first (find-by-x! 1)) => (contains obj1)
    (first (find-by-w 4)) => (contains obj1)
    (first (find-by-w! 4)) => (contains obj1)
    (first (find-by-x 2)) => (contains obj2)
    (first (find-by-x! 2)) => (contains obj2)
    (first (find-by-w 5)) => (contains obj2)
    (first (find-by-w! 5)) => (contains obj2)

    ;; failure
    (resolve 'find-one-by-y) => nil
    (resolve 'find-one-by-y!) => nil

    ;; empty
    (find-one-by-x 3) => nil
    (find-one-by-w 2) => nil
    (find-by-w 2) => '()
    (find-one-by-x! 3) => (throws Exception "Couldn't find row with :x=3 on collection :xs")
    (find-one-by-w! 2) => (throws Exception "Couldn't find row with :w=2 on collection :xs")))

(fact "find-by-id works"
  (let [obj (create! {:x 5 :y 6})
        id (:_id obj)]
    (find-by-id obj) => (contains obj)
    (find-by-id id) => (contains obj)
    (find-by-id (str id)) => (contains obj)
    (find-by-id (congo/object-id (str id))) => (contains obj)
    (find-by-id nil) => nil))

(fact "find-by-ids works"
  (let [obj1 (create! {:x 5 :y 6})
        obj2 (create! {:x 5 :y 6})
        ids (map :_id [obj1 obj2])]
    (find-by-ids [obj1 obj2]) => (contains obj1 obj2)
    (find-by-ids ids) => (contains obj1 obj2)
    (find-by-ids (map str ids)) => (contains obj1 obj2)))

(fact "find-one works"
  (create! {:x 5 :y 6})
  (find-one) => (contains {:x 5 :y 6})
  (find-one :where {:y 6}) => (contains {:x 5 :y 6})
  (find-one :where {:y 7}) => nil)

(fact "keyword works"
  (create! {:x 5 :kw :asd}) => (contains {:x 5 :kw :asd})
  (find-one) => (contains {:x 5 :kw :asd}))

(fact "strings works"
  (create! {:strs {"a b" 1 "x/y" 2}}) => (contains {:strs {"a b" 1 "x/y" 2}})
  (find-one) => (contains {:strs {"a b" 1 "x/y" 2}}))

(fact "strings coerces its children"
  (let [new (create! {:strs {"a b" {:c 1} "x/y" {:e {:f 2}}}})
        found (find-one)]
    new => (contains {:strs {"a b" {:c 1} "x/y" {:e {:f 2}}}})
    (type (:strs new)) => clojure.lang.PersistentArrayMap
    (type (get-in new [:strs "a b"])) => clojure.lang.PersistentArrayMap

    found => (contains {:strs {"a b" {:c 1} "x/y" {:e {:f 2}}}})
    (type (:strs found)) => clojure.lang.PersistentArrayMap
    (type (get-in found [:strs "a b"])) => clojure.lang.PersistentArrayMap))

(fact "dates use joda"
  (let [t (time/now)
        id (:_id (create! {:time t}))]
    (:time (find-by-id id)) => t))


(fact "apply-defaults works"
  (core/apply-defaults [[:x 5] [:y (fn [v] 6)] [:z 10]] {:z 9} nil) => (just {:x 5 :y 6 :z 9}))

(fact "apply-defaults works with only"
  (core/apply-defaults [[:x 5] [:y (fn [v] 6)] [:z 10]] {:z 9} [:z]) => (just {:z 9}))


(fact "default works on creation"
  (create! {:x 7}) => (contains {:dx 5 :dy 6 :dz 7})
  (nu {:x 7}) => (contains {:dx 5 :dy 6 :dz 7}))

(fact "default works on loading"
  (congo/insert! :xs {:x 22})
  (find-one-by-x! 22) => (contains {:dx 5 :dy 6 :dz 22})
  (find-one-by-x 22) => (contains {:dx 5 :dy 6 :dz 22}))

(fact "default works on loading with :only specified as a collection."
      (congo/insert! :xs {:x 22 :y 33 :z 44})
      (find-one-by-x! 22 :only [:x :y :def1]) => (contains {:def1 5}))

(fact "default works on loading with :only specified as a map."
      (congo/insert! :xs {:x 22 :y 33 :z 44})
      (find-one-by-x! 22 :only {:x true :y true :def1 true}) => (contains {:def1 5}))

(fact "default works on loading with :only specified as a map with false values."
      (congo/insert! :xs {:x 22 :y 33 :z 44})
      (find-one-by-x! 22 :only {:x false}) => (contains {:def1 5}))

(fact "default works on loading with :only specified as a map and :_id specified"
      (congo/insert! :xs {:x 22 :y 33 :z 44})
      (find-one-by-x! 22 :only {:x true :def1 true :_id false}) => (contains {:def1 5}))

(fact "defaults work in order"
  (create! {:x 11}) => (contains {:x 11 :def1 5 :def2 6 :def3 7 :def4 11}))

(fact "defaults apply to nil"
  (create! {:x 5}) => (contains {:y nil}))

(fact "transient causes things not to be saved to the DB"
  (create! {:disx 5 :x 12}) => (contains {:disx 5})
  (find-one-by-x 12) =not=> (contains {:disx 5}))

(fact "ensure find-and-modify! works as planned"
  (let [original (create! {:a "b" :c "d" :e "f"})
        r0 (find-and-modify! {:a (:a original)} {:$unset {:c true}})
        r1 (find-and-modify! {:a (:a original)} {:$unset {:e true}} :return-new? true)
        r2 (find-one)]
    r0 => (contains {:c anything})
    r1 =not=> (contains {:e anything})
    r2 =not=> (contains {:c anything :e anything})))

(fact "ensure set-field! works as planned"
  ;; add and check expected values
  (create! {:a "b" :c "d"})
  (let [old (find-one)
        updated (merge old {:set-field-test1 1})]
    old => (contains {:a "b" :c "d"})

    ;; sneakily add an extra field, which shouldn't be fetched during the set-fields
    (set-fields! old {:sneaky-field 1})

    ;; set and check expected values
    (let [result (set-fields! updated {:a "x" :e "f"})
          count (find-count)
          new (find-one)]
      count => 1
      result => (contains (dissoc new :sneaky-field))
      result => (contains {:set-field-test1 1})
      new =not=> (contains {:set-field-test1 1})
      result =not=> (contains {:sneaky-field 1})
      new => (contains {:sneaky-field 1})
      new => (contains {:a "x" :c "d" :e "f" :dx 5 :dy 6}))))

(fact "is-dot-notated? works"
      (core/is-dot-notated? "a.b") => true
      (core/is-dot-notated? "bananagrams") => false)

(fact "verify deep-merge-like-mongo works properly"
  (create! {:food {:summer "salmon" :winter "nuts" :spring {:morning "dandelion" :afternoon "squirrel"}} :name "ursa"})
       (let [old (find-one)]
        old => (contains {:food {:summer "salmon" :winter "nuts" :spring {:morning "dandelion" :afternoon "squirrel"}} :name "ursa"}) ;; just making sure
         (let [new_fields_1 {:food.summer "trout"}
              new_fields_2 {:food {:summer "trout"}}
              new_fields_3 {:food.spring {:morning "sunflower"}}]
           (core/deep-merge-with-like-mongo old new_fields_1 #{}) => (contains {:food {:summer "trout" :winter "nuts" :spring {:morning "dandelion" :afternoon "squirrel"}} :name "ursa"})
           (core/deep-merge-with-like-mongo old new_fields_2 #{}) => (contains {:food {:summer "trout"} :name "ursa"})
           (core/deep-merge-with-like-mongo old new_fields_2 #{}) =not=> (contains {:food {:winter "nuts"}})
           (core/deep-merge-with-like-mongo old new_fields_3 #{}) => (contains {:food {:winter "nuts" :summer "salmon" :spring {:morning "sunflower"}} :name "ursa"})
      true)))

(fact "deep-merge-like-mongo works properly"
  (create! {:strs {"ab" {:c 2}}})
  (let [old (find-one)]
    old => (contains {:strs {"ab" {:c 2}}}) ;; just making sure
    (let [new_fields_1 {:strs.ab.c 3}
          new_fields_2 {:strs.ab 4}]
      (core/deep-merge-with-like-mongo old new_fields_1 #{:strs}) => (contains {:strs {"ab" {:c 3}}})
      (core/deep-merge-with-like-mongo old new_fields_2 #{:strs}) => (contains {:strs {"ab" 4}})
      (core/deep-merge-with-like-mongo old new_fields_2 #{:strs}) =not=> (contains {:strs {"ab" {:c 2}}})
      (-> (core/deep-merge-with-like-mongo old new_fields_2 #{:strs}) :strs :ab) => nil
      true)))

(fact "verify that our deep-nesting works in set-fields!"
  (create! {:food {:summer "salmon" :winter "nuts"} :name "ursa"})
  (let [old (find-one)]
    old => (contains {:food {:summer "salmon" :winter "nuts"} :name "ursa"})
    (let [new_1 (set-fields! old {:food.summer "trout"})
          newer_1 (find-one)
          new_2 (set-fields! old {:food {:summer "trout"}})
          newer_2 (find-one)]
      new_1 => newer_1
      new_1 => (contains {:food {:summer "trout" :winter "nuts"} :name "ursa"})
      new_2 => newer_2
      new_2 => (contains {:food {:summer "trout"} :name "ursa"})
      new_2 =not=> (contains {:food {:winter "nuts"}}))))

(fact "ensure unset-field! works as planned"
  ;; add and check expected values
  (create! {:a "b" :c "d"})
  (let [old (find-one)
        updated (merge old {:set-field-test1 1})]
    old => (contains {:a "b" :c "d"})

    ;; set and check expected values
    (let [result (unset-fields! updated [:a :e])
          count (find-count)
          new (find-one)]
      count => 1
      result => (contains new)
      result => (contains {:set-field-test1 1})
      new =not=> (contains {:set-field-test1 1})
      new => (contains {:c "d" :dx 5 :dy 6})
      new =not=> (contains {:a anything})
      new =not=> (contains {:e anything}))))

(fact "replace! works"
  (find-count) => 0
  (let [x (create! {:a :b :x :w})]
    (replace! x {:c :d :a :B}))
  (find-count) => 1
  (find-one) => (contains {:c "d" :a "B"})
  (find-one) =not=> (contains {:x "w"}))

(fact "dont try to serialize transiented"
  (create! {:disx (fn [] "x")}) => anything)

(fact "check validations work"
  (let [x (create! {:valid-pos 5})]
    (valid? x) => true
    (validate! x) => x))

(fact "check validations fail properly"
  (create! {:valid-pos -1}) => (throws Exception "Should be positive"))

(fact "all works"
  (all) => (list)

  (create! {:x 5 :y 6})
  (count (all)) => 1

  (create! {:y 7 :z 8})
  (let [result (all)]
    result => seq?
    (count result) => 2
    result => (contains [(contains {:x 5 :y 6}) (contains {:y 7 :z 8})])))

(fact "where works"
  (where {:x 5}) => (list)

  (create! {:x 5 :y 6})
  (-> {:x 5} where first) => (contains {:x 5 :y 6})
  (where {:x 6}) => (list)

  (create! {:y 7 :z 8})
  (let [result (where {:x 5 :y 6})]
    result => seq?
    (count result) => 1
    (first result) => (contains {:x 5 :y 6}))

  ;; check defaults
  (count (where {:dx 5})) => 2)

(fact "only works"
  (create! {:x 5 :y 6 :z 7})
  (find-one :only [:x]) => (just {:x 5 :_id anything}))

(fact "incomplete? works"
  (create! {:x 5 :y 6 :z 7})
  (meta (find-one)) => (just {:incomplete? false})
  (meta (find-one :only [:x])) => (just {:incomplete? true})
  (meta (find-one :only {:y false})) => (just {:incomplete? true})
  (meta (set-fields! (find-one) {:a :b})) => (just {:incomplete? false})
  (meta (set-fields! (find-one :only [:x]) {:a :b})) => (just {:incomplete? true}))

(fact "`all and :keywords work together"
  (create! {:kw "state"})
  (-> (all) first :kw) => :state)

(fact "save! works"
  (let [row (create! {:x 7})
        row (merge row {:y 45 :kw :g})
        saved (save! row)]
    saved => (contains {:y 45 :kw :g})

    (find-one) => (contains {:y 45 :kw :g})))


(future-fact "validators cause save to fail coreectly")
(future-fact "validators dont prevent valid objects from saving")

(future-fact "validators cause set-fields to fail coreectly")
(future-fact "validators dont prevent valid objects from having their fields set")

(future-fact "validators cause replace! to fail coreectly")
(future-fact "validators dont prevent valid objects from being replaced")


(fact "find-by-id has an error with an invalid id causes an error"
  (find-by-id "") => (throws Exception #"Got empty string")

  (find-by-ids [""]) => (throws Exception #"Got empty string")
  (find-by-ids ["012345678901234568790123" nil]) => (throws Exception #"Expected id, got nil"))

(fact "push! works"
  (let [orig (create! {:a []})
        new (push! orig :a "b")
        refound (find-one)]
    (:a orig) => []
    (:a new) => ["b"]
    (:a refound) => ["b"]))

(fact "pull! works"
    (let [orig (create! {:a ["a" "b" "c"]})
        new (pull! orig :a "b")
        refound (find-one)]
    (:a orig) => ["a" "b" "c"]
    (:a new) => ["a" "c"]
    (:a refound) => ["a" "c"]))

(fact "add-to-set! works"
  (let [orig (create! {:a []})
        new (add-to-set! orig :a "b")
        _ (add-to-set! orig :a "b")
        _ (add-to-set! orig :a "c")
        _ (add-to-set! orig :a "b")
        _ (add-to-set! orig :a "b")
        _ (add-to-set! orig :a "b")
        new (add-to-set! new :a "b")
        new (add-to-set! new :a "c")
        new (add-to-set! new :a "b")
        new (add-to-set! new :a "b")
        new (add-to-set! new :a "b")
        refound (find-one)]
    (:a orig) => []
    (:a new) => ["b" "c"]
    (:a refound) => ["b" "c"]))

(fact "get-hooks works"
  (let [foo (fn [x] x)]
    (mongofinil.core/get-hooks :pre
                               {:create {:pre foo}
                                :load {:post foo}
                                :update {:post foo}}
                               {:create [:pre]
                                :load [:post]}) => [foo]))

(fact "create hook is called properly"
  (bond/with-spy [create-hook load-hook]
    (create! {:a []})
    (-> create-hook bond/calls count) => 1
    (-> create-hook bond/calls first :args first) => (contains {:a [] :dx 5})
    (-> load-hook bond/calls count) => 1
    (-> load-hook bond/calls first :args first) => (contains {:a []})))

(fact "wrap-profile handles clock skew"
  (core/wrap-profile println 1 "foo" "bar") => anything
  (let [counter (atom 0)
        before (time/minus (time/now) (time/seconds 2))
        after (time/now)
        stateful-time (fn []
                        (swap! counter inc)
                        (if (zero? (mod @counter 2))
                          before
                          after))]
    (with-redefs [time/now stateful-time]
      ((core/wrap-profile println 1 "foo" "bar")) => anything)))

(fact "wrap-middleware-works"
  (letfn [(foo [x] x)]
    (core/wrap-fn-middleware foo nil) => #(= foo %)
    (core/wrap-fn-middleware foo fn-middleware) =not=> #(= foo %)
    (core/wrap-fn-middleware foo :keyword) => (throws Error)))

(fact "fns-are-wrapped-with-middleware"
  (bond/with-spy [spyable-middleware-fn]
    (create! {:y 5}) => (contains {:y 5})
    (-> spyable-middleware-fn bond/calls count) => 1
    (-> spyable-middleware-fn bond/calls first :args first) => (contains {:y 5})))

;;; This works in congomongo 1.9
;; (future-fact "calling create twice on a row with an index should raise an error"
;;   (let [obj {:unique1 "abc@abc.com"}
;;         inserted (congo/insert! :xs (assoc obj :_id (ObjectId.)))]

;;     (congo/add-index! :xs [:unique1] :unique true)

;;     ;; check the index works
;;     (find-count) => 1
;;     (congo/insert! :xs obj) => (throws com.mongodb.MongoException$DuplicateKey #"duplicate key error")
;;     (find-count) => 1

;;     ;; check we raise an error
;;     (create! obj) => (throws com.mongodb.MongoException$DuplicateKey #"duplicate key error")
;;     (find-count) => 1


;;     ;; check we can add again if the constraint is removed
;;     (set-fields! inserted {:unique1 "x"})
;;     (find-count) => 1
;;     (create! obj) => map?
;;     (find-count) => 2))


(future-fact "transient doesnt stop things being loaded from the DB"
             (congo/insert! :xs {:disx 55 :x 55})
             (find-by-x 55) => (contains {:disx 55}))

(future-fact "refresh function exists and works (refreshes from DB")


(future-fact "incorrectly named attributes are caught"
             (eval `(core/defmodel :ys :fields [{:a :b}])) => (throws Exception)
             (eval `(core/defmodel :ys :fields [{:name :b :unexpected-field :y}])) => (throws Exception))

(future-fact "calling functions with the wrong signatures should give slightly useful error messages")
