(ns uchiwake.methods.uchiwake-edn
  "uchiwake 内訳 — shared minimal EDN reader + datom classifier + GTIN helpers.
  1:1 Clojure port of `methods/uchiwake_edn.py` (ADR-2606081800).

  Ported from the kabuto/watatsuna/inochi readers (same subset: vectors [], maps {},
  :keyword, \"string\", number, bool, nil). Keeps uchiwake dependency-free. Keywords are
  kept as \":ns/name\" STRINGS (NOT clojure keywords) so the whole pipeline stays string-keyed,
  byte-for-byte the same as the Python port.

  GTIN (G5/G1, do-not-weaken): `normalize-gtin` left-zero-pads to GTIN-14;
  `gtin-check-digit-ok` reproduces the GS1 mod-10 weighted (3,1,3,1…) checksum EXACTLY —
  rightmost body digit weighted ×3, alternating. Portable .cljc."
  (:require [kotoba.lang.text :as str]))

;; ── minimal EDN reader (subset) ──────────────────────────────────────────────
;; _TOK = re.compile(r'[\s,]+|;[^\n]*|(\[|\]|\{|\}|"(?:\\.|[^"\\])*"|[^\s,\[\]{}]+)')
(def ^:private tok-re
  #"[\s,]+|;[^\n]*|(\[|\]|\{|\}|\"(?:\\.|[^\"\\])*\"|[^\s,\[\]{}]+)")

(defn tokens
  "Lazy seq of significant tokens (group 1 of each tok-re match that captured)."
  [s]
  (let [m (re-matcher tok-re s)]
    ((fn step []
       (lazy-seq
        (when (.find m)
          (let [t (.group m 1)]
            (if (nil? t)
              (step)
              (cons t (step))))))))))

(defn atom-of
  "Port of _atom: \"…\" → unescaped string; true/false/nil → bool/nil; \":…\" kept as string;
  int → long; else float; else raw string."
  [t]
  (cond
    (str/starts-with? t "\"")
    (-> (subs t 1 (dec (count t)))
        (str/replace "\\\"" "\"")
        (str/replace "\\\\" "\\"))
    (= t "true") true
    (= t "false") false
    (= t "nil") nil
    (str/starts-with? t ":") t
    :else
    (let [as-long (try (Long/parseLong t) (catch #?(:clj Exception :cljs :default) _ ::nan))]
      (if (not= as-long ::nan)
        as-long
        (let [as-dbl (try (Double/parseDouble t) (catch #?(:clj Exception :cljs :default) _ ::nan))]
          (if (not= as-dbl ::nan) as-dbl t))))))

(def ^:private end-marker ::end)

(defn- parse-step
  "Consume one form from the token vector at index i. Returns [value next-i] or
  [end-marker next-i] when a closing ] or } is hit (matching _parse's _END sentinel)."
  [toks i]
  (let [t (nth toks i)
        i (inc i)]
    (cond
      (= t "[")
      (loop [i i, out []]
        (let [[x i] (parse-step toks i)]
          (if (= x end-marker)
            [out i]
            (recur i (conj out x)))))

      (= t "{")
      ;; Track first-touch key order in ::order meta so maps with >8 keys (which Clojure
      ;; promotes to an UNORDERED hash-map) still reproduce Python dict insertion order —
      ;; required for byte-identical kotoba tx CIDs (kotoba.cljc reads keys via keys-in-order).
      (loop [i i, out (vary-meta {} assoc ::order [])]
        (let [[k i] (parse-step toks i)]
          (if (= k end-marker)
            [out i]
            (let [[v i] (parse-step toks i)]
              (recur i (vary-meta (assoc out k v) update ::order conj k))))))

      (or (= t "]") (= t "}"))
      [end-marker i]

      :else
      [(atom-of t) i])))

(defn read-edn
  "Parse the first top-level form from EDN text (matches _parse(_tokens(text)))."
  [text]
  (let [toks (vec (tokens text))]
    (first (parse-step toks 0))))

#?(:clj
   (defn load-edn
     "Read + parse an EDN file (1:1 with load_edn). File I/O only at this edge."
     [path]
     (read-edn (slurp (str path)))))

;; ── classify the flat datom vector into entity buckets ───────────────────────
(defn classify
  "Return a map of entity buckets keyed/listed by uchiwake entity kind (1:1 with classify).
  Node buckets (:products/:parts/:materials) are insertion-ordered (array-map + ::order meta)
  to mirror Python dict insertion order even beyond 8 keys; edge buckets are vectors."
  [rows]
  (let [omap (fn [m] (vary-meta m assoc ::order []))
        oassoc (fn [m k v]
                 (if (contains? m k)
                   (assoc m k v)
                   (vary-meta (assoc m k v) update ::order conj k)))]
    (reduce
     (fn [out r]
       (if-not (map? r)
         out
         (cond
           (contains? r ":product/id")  (update out :products  oassoc (get r ":product/id") r)
           (contains? r ":part/id")     (update out :parts     oassoc (get r ":part/id") r)
           (contains? r ":material/id") (update out :materials oassoc (get r ":material/id") r)
           (contains? r ":bom.edge/id")         (update out :bom       conj r)
           (contains? r ":process.step/id")     (update out :process   conj r)
           (contains? r ":logistics.leg/id")    (update out :logistics conj r)
           (contains? r ":design.ref/id")       (update out :design    conj r)
           (contains? r ":company.ownership/id") (update out :ownership conj r)
           :else out)))
     {:products (omap (array-map)) :parts (omap (array-map)) :materials (omap (array-map))
      :bom [] :process [] :logistics [] :design [] :ownership []}
     rows)))

(defn keys-in-order
  "Keys of a classify node bucket in first-touch insertion order (mirrors Python dict order)."
  [m]
  (or (::order (meta m)) (keys m)))

(defn edn-str
  "EDN-escape a string into a quoted EDN string literal (1:1 with edn_str)."
  [s]
  (str "\"" (-> (str s) (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) "\""))

(defn py-round
  "Python round(x, n) — HALF_EVEN (banker's rounding) on the EXACT double value."
  [x n]
  (-> (java.math.BigDecimal. (double x))
      (.setScale n java.math.RoundingMode/HALF_EVEN)
      .doubleValue))

(defn py-round-2 [x] (py-round x 2))

(defn py-float-str
  "str(float) — shortest round-trip repr (Double/toString matches Python repr for our values)."
  [x]
  (str (double x)))

;; ── GTIN helpers ─────────────────────────────────────────────────────────────
(defn- digits
  "''.join(ch for ch in str(gtin) if ch.isdigit())"
  [gtin]
  (apply str (filter #(Character/isDigit ^char %) (str gtin))))

(defn normalize-gtin
  "Left-zero-pad any GTIN-8/12/13 to the canonical 14-digit GTIN-14 (1:1 with normalize_gtin)."
  [gtin]
  (let [d (digits gtin)]
    (if (>= (count d) 14)
      d
      (str (apply str (repeat (- 14 (count d)) \0)) d))))

(defn gtin-check-digit-ok
  "Validate the GS1 mod-10 check digit of a GTIN (length 8/12/13/14). 1:1 with
  gtin_check_digit_ok: rightmost body digit weighted ×3, alternating (3,1,3,1…)."
  [gtin]
  (let [d (digits gtin)]
    (if-not (contains? #{8 12 13 14} (count d))
      false
      (let [body  (subs d 0 (dec (count d)))
            check (- (int (.charAt d (dec (count d)))) (int \0))
            rev   (reverse body)
            total (reduce + 0
                          (map-indexed
                           (fn [i ch]
                             (* (- (int ch) (int \0)) (if (even? i) 3 1)))
                           rev))]
        (= (mod (- 10 (mod total 10)) 10) check)))))
