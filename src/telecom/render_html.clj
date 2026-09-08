(ns telecom.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this repo. What was committed here
  before was a HAND-WRITTEN FAKE: a page with an `E.164 validation` table
  (`+442079460958`, `bad`), a `Recent calls (CDR)` table (`C1`, `+8190A`,
  `+8190B`, 120s) and a `Recent SMS` table (`S1`, body `hi`). This actor
  has no CDR concept, no SMS concept, and no such lines -- `telecom.store/
  demo-data` seeds `line-1`..`line-4` (Sakura Community Network, Atlantis
  Co-op, 鈴木ライン, 田中回線). No generator ever produced that page; it was
  typed by hand. It is replaced wholesale here.

  This namespace drives the REAL actor stack -- `telecom.operation` (a
  compiled langgraph StateGraph) -> `telecom.governor` -> `telecom.store`
  -- through a scenario adapted from this repo's own `telecom.sim` demo
  driver (`clojure -M:dev:run`, run BEFORE this file was written to
  confirm it produces a sensible ledger against the real seeded line ids),
  then renders the resulting store, audit ledger and actuation registers.

  NOTHING on the page is hand-typed. Every line id, holder name, E.164
  number, jurisdiction, sequence number, disposition, hold rule and hold
  detail string is read back out of the real store/ledger the run
  produced. The action-gate table, the phase ladder and the numbering-plan
  spec-basis catalog are derived from the live `telecom.governor` /
  `.phase` / `.facts` vars rather than described in prose, so they cannot
  drift away from the code. The scenario INPUTS (which op against which
  line id) are of course authored -- that is what a scenario is -- but no
  OUTPUT is.

  Where the page cannot honestly show something, it says so instead of
  inventing it: see `approver-attribution` below.

  ## Why this scenario

  It walks `line-1` (JPN, valid E.164, no billing dispute) through a full
  clean lifecycle -- intake (auto-commits at phase 3), identity
  verification, billing-dispute screening, number provisioning and
  billing-record suppression (the last two ALWAYS escalate at every
  phase) -- and then exercises ALL SIX of the Telecom Access Governor's
  HARD checks, each of which HOLDS without ever reaching a human:

    1. `:no-spec-basis`              -- `line-2`, jurisdiction ATL, absent
                                        from `telecom.facts/catalog`, so
                                        the advisor cites nothing
    2. `:evidence-incomplete`        -- provisioning `line-2`, whose
                                        identity verification never
                                        committed (its ATL verify HARD-held
                                        at step 1), so no evidence is on
                                        file
    3. `:e164-format-invalid`        -- provisioning `line-3`, whose own
                                        recorded number `0312345678` has no
                                        leading `+`. Verified & approved
                                        first, so this rule fires ALONE
                                        rather than alongside 2.
    4. `:billing-dispute-unresolved` -- screening `line-4`, which reports an
                                        unresolved dispute on its own
                                        finding
    5. `:already-provisioned`        -- provisioning `line-1` a second time
    6. `:already-suppressed`         -- suppressing `line-1` a second time

  ## Determinism

  Every collaborator in the path is pure or deterministic: the mock
  advisor is a `case` over the request, the registry's reference numbers
  are jurisdiction-scoped zero-padded sequences, and no code in `src/`
  reads a clock or a RNG. Every map or set iterated for the page
  (`facts/catalog`, `phase/phases`, `phase/write-ops`,
  `governor/high-stakes`) is explicitly sorted here rather than iterated
  in hash order. The page therefore contains NO timestamp and NO generated
  id, and two consecutive runs are byte-identical (verified with `cmp`).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [kotoba.lang.text :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [telecom.facts :as facts]
            [telecom.governor :as governor]
            [telecom.operation :as op]
            [telecom.phase :as phase]
            [telecom.store :as store]))

(def ^:private operator
  "The same operator context this repo's own `sim` driver uses."
  {:actor-id "op-1" :actor-role :telecom-operator :phase 3})

;; ----------------------------- driving the REAL actor -----------------------------

(defn- record!
  "Append one finished graph run to the ordered run log. `result` is the
  raw `langgraph.graph/run*` return value -- everything rendered from it
  is real actor output."
  [runs tid request result]
  (swap! runs conj {:tid tid
                    :request request
                    :audit (vec (get-in result [:state :audit]))
                    :disposition (get-in result [:state :disposition])})
  result)

(defn- exec!
  "One operation, no human in the loop (auto-commit or HARD hold)."
  [runs actor tid request]
  (record! runs tid request
           (g/run* actor {:request request :context operator} {:thread-id tid})))

(defn- run-approve!
  "One operation that the phase gate / governor escalates, then resumed by
  a human approval. The resumed result carries the FULL accumulated audit
  (`:audit`'s reducer is `into`, restored from the checkpointer), so only
  the resumed result is recorded."
  [runs actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid})
  (record! runs tid request
           (g/run* actor {:approval {:status :approved :by "op-1"}}
                   {:thread-id tid :resume? true})))

(defn run-demo!
  "Runs a fresh seeded store through the scenario described in the ns
  docstring. Returns `{:db :runs}` -- `:runs` is the ordered log of real
  graph results, `:db` the real store the actor wrote."
  []
  (let [db    (store/seed-db)
        actor (op/build db)
        runs  (atom [])]

    ;; --- clean lifecycle on line-1 (JPN, valid E.164, no billing dispute) ---
    (exec! runs actor "t01"
           {:op :line/intake :subject "line-1"
            :patch {:id "line-1" :holder-name "Sakura Community Network"}})
    (run-approve! runs actor "t02" {:op :identity/verify :subject "line-1"})
    (run-approve! runs actor "t03" {:op :billing/screen :subject "line-1"})
    ;; Both actuations ALWAYS escalate -- absent from every phase's :auto set
    ;; AND flagged high-stakes by the governor. Two independent layers.
    (run-approve! runs actor "t04" {:op :actuation/provision-number :subject "line-1"})
    (run-approve! runs actor "t05" {:op :actuation/suppress-billing-record :subject "line-1"})

    ;; --- all six HARD checks, none of which ever reaches a human ---
    ;; 1. line-2's own jurisdiction IS "ATL", absent from telecom.facts/catalog.
    (exec! runs actor "t06" {:op :identity/verify :subject "line-2"})
    ;; 2. ...so line-2 has no committed verification, and provisioning it
    ;;    hits the evidence gate (its E.164 number is perfectly valid).
    (exec! runs actor "t07" {:op :actuation/provision-number :subject "line-2"})
    ;; 3. line-3 IS verified & approved first, so the format check fires alone.
    (run-approve! runs actor "t08" {:op :identity/verify :subject "line-3"})
    (exec! runs actor "t09" {:op :actuation/provision-number :subject "line-3"})
    ;; 4. line-4 reports its own unresolved dispute; the screening op holds itself.
    (exec! runs actor "t10" {:op :billing/screen :subject "line-4"})
    ;; 5./6. double-actuation guards on the already-processed line-1.
    (exec! runs actor "t11" {:op :actuation/provision-number :subject "line-1"})
    (exec! runs actor "t12" {:op :actuation/suppress-billing-record :subject "line-1"})

    {:db db :runs @runs}))

;; ----------------------------- rendering helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw-str [v] (if (keyword? v) (name v) (str v)))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- n-cell [v] (str "<span class=\"num\">" (esc v) "</span>"))

(defn- dash [] "<span class=\"muted\">&mdash;</span>")

(defn- yes-no [v]
  (if (true? v)
    "<span class=\"ok\">yes</span>"
    "<span class=\"muted\">no</span>"))

(defn- fact-of [audit t] (first (filter #(= t (:t %)) audit)))

(defn- row [& cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- rows [xs] (str/join "\n" xs))

(defn- op-codes
  "A deterministic, sorted `<code>` list of an op set."
  [ops]
  (if (seq ops)
    (str/join " " (map #(code (kw-str %)) (sort-by kw-str ops)))
    "<span class=\"muted\">none</span>"))

(defn- section [title lead headers body-rows]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       "    <p class=\"muted\">" lead "</p>\n"
       "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" % "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n" (rows body-rows) "\n      </tbody>\n"
       "    </table>\n"
       "  </section>\n"))

;; ----------------------------- outcome classification -----------------------------

(defn- outcome
  "Classify one real run from its own audit trail. Never from a literal."
  [{:keys [audit disposition]}]
  (let [hold (fact-of audit :governor-hold)]
    (cond
      hold {:kind :hard-hold :violations (:violations hold)}

      (fact-of audit :approval-granted)
      {:kind :approved
       :reason (:reason (fact-of audit :approval-requested))
       :by (:by (fact-of audit :approval-granted))}

      (fact-of audit :approval-requested)
      {:kind :awaiting :reason (:reason (fact-of audit :approval-requested))}

      (= :commit disposition) {:kind :auto-commit}
      :else {:kind :other})))

(defn- outcome-cell [o]
  (case (:kind o)
    :hard-hold (str "<span class=\"critical\">HARD hold &middot; "
                    (esc (str/join ", " (map (comp kw-str :rule) (:violations o))))
                    "</span>")
    :approved (str "<span class=\"ok\">escalated (" (esc (kw-str (:reason o)))
                   ") &rarr; approved by " (esc (:by o)) "</span>")
    :awaiting (str "<span class=\"warn\">awaiting human approval &middot; "
                   (esc (kw-str (:reason o))) "</span>")
    :auto-commit "<span class=\"ok\">auto-commit (governor-clean)</span>"
    "<span class=\"muted\">in progress</span>"))

(defn- detail-cell [o]
  (case (:kind o)
    :hard-hold (esc (str/join " / " (map :detail (:violations o))))
    :approved "<span class=\"muted\">human in the loop before commit</span>"
    :awaiting "<span class=\"muted\">paused at :request-approval</span>"
    (dash)))

(defn- holds
  "The HARD `:governor-hold` facts the run actually wrote to the ledger."
  [db]
  (filterv #(= :governor-hold (:t %)) (store/ledger db)))

;; ----------------------------- approver attribution (MEASURED) -----------------------------

(defn- deep-key-names
  "Every key name appearing anywhere in a nested structure, as strings.
  Used to ask the SSoT what it actually holds instead of assuming."
  [x]
  (cond
    (map? x) (into (into #{} (map kw-str) (keys x))
                   (mapcat deep-key-names (vals x)))
    (sequential? x) (into #{} (mapcat deep-key-names x))
    :else #{}))

(defn- approver-key?
  [k]
  (let [n (str/replace (str/lower (str k)) "_" "-")]
    (or (contains? #{"approver" "approved-by" "approved-by-id" "approvedby"} n)
        (str/starts-with? n "approved-by"))))

(defn- deep-approver-values
  "Every value stored under an approver-looking key, anywhere in `x`."
  [x]
  (cond
    (map? x) (into (into #{} (keep (fn [[k v]] (when (approver-key? (kw-str k)) v))) x)
                   (mapcat deep-approver-values (vals x)))
    (sequential? x) (into #{} (mapcat deep-approver-values x))
    :else #{}))

(defn- register-probe
  "Probe ONE named register for the human approver's id. Returns
  `{:register :retains? :values}` measured against the real post-run store
  contents -- never asserted."
  [label items]
  {:register label
   :retains? (boolean (some approver-key? (deep-key-names items)))
   :values   (vec (sort (map str (deep-approver-values items))))})

(defn- approver-attribution
  "DERIVED, per-register honest disclosure about where the human approver's
  id actually survives a `:request-approval` handoff.

  `operation`'s `:request-approval` node attaches the approver at
  `[:payload :approved-by]` on the record (leaving `[:value]` untouched),
  so whether it survives depends ENTIRELY on which of the two keys each
  `store/commit-record!` branch happens to read. That differs per effect in
  this repo, so a single yes/no would be a lie either way.

  Rather than assert the answer in prose (which would silently go stale the
  day the store changes), this walks every register in the real store at
  render time and reports what it finds. The ledger is probed the same way
  (`:approval-granted` never reaches it -- only `:commit` and `:hold` nodes
  append, and they append `:committed` / `:governor-hold` / `:approval-
  rejected` facts)."
  [db runs]
  (let [lines (store/all-lines db)
        ids   (map :id lines)]
    {:approvers (vec (sort (into #{} (keep #(:by (fact-of (:audit %) :approval-granted))) runs)))
     :probes
     [(register-probe "identity-verification register (<code>:verification/set</code>)"
                      (vec (keep #(store/identity-verification-of db %) ids)))
      (register-probe "billing-screening register (<code>:billing-screen/set</code>)"
                      (vec (keep #(store/billing-screen-of db %) ids)))
      (register-probe "number-provisioning history (<code>:line/mark-provisioned</code>)"
                      (vec (store/provisioning-history db)))
      (register-probe "billing-suppression history (<code>:line/mark-suppressed</code>)"
                      (vec (store/suppression-history db)))
      (register-probe "line directory (<code>:line/upsert</code>)" (vec lines))
      (register-probe "audit ledger" (vec (store/ledger db)))]}))

(defn- attribution-section
  "Renders the approver-attribution disclosure from the MEASURED per-register
  facts, so the claim tracks the code. The demo never prints an approver as
  though a register held one when it does not -- and, equally, never stays
  silent about one that IS retained."
  [{:keys [approvers probes]}]
  (let [retaining (filter :retains? probes)
        dropping  (remove :retains? probes)]
    (str "  <section class=\"card\">\n"
         "    <h2>Approver attribution &mdash; what each register does and does not retain</h2>\n"
         "    <p class=\"muted\">Measured against the real store at render time: every register is "
         "walked and scanned for an approver key, so this disclosure cannot drift away from the code. "
         "<code>operation</code>&rsquo;s <code>:request-approval</code> node attaches the approver at "
         "<code>[:payload :approved-by]</code> and leaves <code>[:value]</code> untouched, so whether it "
         "survives depends on which key each <code>store/commit-record!</code> branch reads &mdash; "
         "which is <strong>not uniform in this repo</strong>.</p>\n"
         "    <table>\n"
         "      <thead><tr><th>Register</th><th>Retains approver?</th><th>Approver id(s) actually stored</th></tr></thead>\n"
         "      <tbody>\n"
         (rows (for [{:keys [register retains? values]} probes]
                 (row register
                      (if retains?
                        "<span class=\"ok\">yes</span>"
                        "<span class=\"critical\">no</span>")
                      (if (seq values)
                        (str/join " " (map code values))
                        (dash)))))
         "\n      </tbody>\n    </table>\n"
         "    <p>"
         (cond
           (empty? approvers)
           "This run produced no human approval, so there is no approver to attribute."

           (empty? dropping)
           (str "Every register retains the approver "
                (str/join " " (map code approvers)) ".")

           (empty? retaining)
           (str "<strong>No register retains the approver.</strong> The &ldquo;approved by&rdquo; "
                "text in <em>Operation dispositions</em> above is joined from each run&rsquo;s own "
                "<code>:approval-granted</code> audit fact <em>(audit only &mdash; not retained in "
                "the store record)</em>.")

           :else
           (str "<strong>Attribution is split.</strong> "
                (count retaining) " of " (count probes) " registers retain the approver "
                "(those whose <code>commit-record!</code> branch reads <code>:payload</code>); "
                (count dropping) " do not &mdash; the two actuation histories are rebuilt from "
                "<code>telecom.registry</code> and read neither <code>:value</code> nor "
                "<code>:payload</code>, <code>:line/upsert</code> reads <code>:value</code> (which "
                "never carries the approver), and the <code>:commit</code> node appends only its "
                "<code>:committed</code> fact to the ledger, never the <code>:approval-granted</code> "
                "fact that carries <code>:by</code>. For those rows the &ldquo;approved by&rdquo; text "
                "in <em>Operation dispositions</em> above is joined from the run&rsquo;s own audit "
                "trail <em>(audit only &mdash; not retained in the store record)</em>, which is stated "
                "here plainly rather than left to look like nobody approved."))
         "</p>\n"
         "  </section>\n")))

;; ----------------------------- sections (all derived) -----------------------------

(defn- line-rows [db]
  (for [{:keys [id holder-name e164-number jurisdiction status
                billing-dispute-unresolved? number-provisioned?
                billing-record-suppressed? provisioning-number suppression-number]}
        (store/all-lines db)]
    (row (code id)
         (esc holder-name)
         (code e164-number)
         (esc jurisdiction)
         (esc (kw-str status))
         (if (true? billing-dispute-unresolved?)
           "<span class=\"critical\">unresolved</span>"
           "<span class=\"ok\">none open</span>")
         (yes-no number-provisioned?)
         (if provisioning-number (code provisioning-number) (dash))
         (yes-no billing-record-suppressed?)
         (if suppression-number (code suppression-number) (dash)))))

(defn- run-rows [db runs]
  (for [{:keys [tid request] :as r} runs
        :let [o (outcome r)
              ln (store/line db (:subject request))]]
    (row (code tid)
         (code (kw-str (:op request)))
         (code (:subject request))
         (esc (or (:jurisdiction ln) "n/a"))
         (outcome-cell o)
         (detail-cell o))))

(defn- gate-rows
  "The action gate, DERIVED from the live `phase/write-ops`,
  `phase/phases` and `governor/high-stakes` vars -- not a prose
  description that could drift away from the code."
  []
  (let [auto3 (get-in phase/phases [3 :auto])]
    (for [o (sort-by kw-str phase/write-ops)
          :let [first-write-phase (first (for [p (sort (keys phase/phases))
                                               :when (contains? (:writes (get phase/phases p)) o)]
                                           p))]]
      (row (code (kw-str o))
           (if first-write-phase (n-cell first-write-phase) "<span class=\"muted\">never</span>")
           (if (contains? auto3 o)
             "<span class=\"ok\">may auto-commit when governor-clean</span>"
             "<span class=\"warn\">human approval, every phase</span>")
           (if (contains? governor/high-stakes o)
             "<span class=\"warn\">always high-stakes (real-world act)</span>"
             "<span class=\"muted\">no</span>")))))

(defn- phase-rows []
  (for [p (sort (keys phase/phases))
        :let [{:keys [label writes auto]} (get phase/phases p)]]
    (row (n-cell p) (esc label) (op-codes writes) (op-codes auto))))

(defn- spec-basis-rows
  "The numbering-plan catalog, read straight out of `telecom.facts/catalog`.
  A jurisdiction absent from this table has NO spec-basis, and the governor
  holds any identity-verification proposal against it."
  []
  (for [iso3 (sort (keys facts/catalog))
        :let [{:keys [name owner-authority legal-basis provenance required-evidence]}
              (facts/spec-basis iso3)]]
    (row (code iso3)
         (esc name)
         (esc owner-authority)
         (esc legal-basis)
         (str "<a href=\"" (esc provenance) "\">" (esc provenance) "</a>")
         (n-cell (count required-evidence)))))

(defn- verification-rows [db]
  (for [{:keys [id]} (store/all-lines db)
        :let [v (store/identity-verification-of db id)]
        :when v]
    (row (code id)
         (esc (:jurisdiction v))
         (n-cell (count (:checklist v)))
         (yes-no (boolean (facts/required-evidence-satisfied? (:jurisdiction v) (:checklist v))))
         (if (:spec-basis v) (esc (:spec-basis v)) (dash))
         (if-let [a (:approved-by v)] (code a) (dash)))))

(defn- screening-rows [db]
  (for [{:keys [id]} (store/all-lines db)
        :let [s (store/billing-screen-of db id)]
        :when s]
    (row (code id)
         (if (= :unresolved (:verdict s))
           "<span class=\"critical\">unresolved</span>"
           (str "<span class=\"ok\">" (esc (kw-str (:verdict s))) "</span>"))
         (if-let [a (:approved-by s)] (code a) (dash)))))

(defn- ledger-rows [db]
  (for [{:keys [t op subject disposition basis violations summary]} (store/ledger db)]
    (row (case t
           :committed "<span class=\"ok\">committed</span>"
           :governor-hold "<span class=\"critical\">governor-hold</span>"
           :approval-rejected "<span class=\"critical\">approval-rejected</span>"
           (esc (kw-str t)))
         (code (kw-str op))
         (code subject)
         (esc (kw-str disposition))
         (if (seq violations)
           (esc (str/join ", " (map (comp kw-str :rule) violations)))
           (esc (str/join " ; " (map kw-str basis))))
         (if summary (esc summary) (dash)))))

(defn- artifact-rows [history id-key]
  (for [r history]
    (row (code (get r "record_id"))
         (esc (get r "kind"))
         (code (get r id-key))
         (esc (get r "jurisdiction"))
         (yes-no (get r "immutable")))))

;; ----------------------------- the document -----------------------------

(defn render
  "Renders the whole operator console from a `run-demo!` result. Takes no
  clock and no seed: identical input -> identical bytes."
  [{:keys [db runs]}]
  (let [ledger    (vec (store/ledger db))
        outcomes  (mapv outcome runs)
        hs        (holds db)
        committed (filterv #(= :committed (:t %)) ledger)
        approved  (filterv #(= :approved (:kind %)) outcomes)
        auto      (filterv #(= :auto-commit (:kind %)) outcomes)
        cov       (facts/coverage)
        att       (approver-attribution db runs)
        rules     (into #{} (mapcat (fn [h] (map :rule (:violations h))) hs))]
    (str
     "<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
     "<meta name=\"color-scheme\" content=\"light\">"
     "<title>cloud-itonami-isic-6190 &middot; wired, wireless and satellite telecommunications "
     "&mdash; operator console</title>"
     "<style>" (jp-go-dds.skin/dds+skin) "</style></head><body>\n"

     "<header class=\"bar\">\n"
     "  <h1>Telecommunications &mdash; wired, wireless &amp; satellite (ISIC 6190) &mdash; Operator Console</h1>\n"
     "</header>\n"
     "<p><span class=\"badge\">read-only sample</span> "
     "<span class=\"badge\">governor-gated</span> "
     "<span class=\"badge\">number provisioning &amp; billing suppression always human-approved</span></p>\n"
     "<p class=\"subtitle\">Generated at build time by <code>telecom.render-html</code> "
     "(<code>clojure -M:dev:render-html</code>) by actually running the compiled "
     "<code>telecom.operation</code> StateGraph over a freshly seeded store. Every value below was "
     "read back out of that run &mdash; there is no mock markup on this page, and no timestamp, so "
     "successive regenerations are byte-identical.</p>\n"

     "<main>\n"

     (section "Run summary"
              "Counted from the real audit ledger and the real graph results, not asserted."
              ["Measure" "Count"]
              [(row "lines in the SSoT" (n-cell (count (store/all-lines db))))
               (row "graph runs in this scenario" (n-cell (count runs)))
               (row "<span class=\"ok\">auto-commits (governor-clean, phase 3)</span>" (n-cell (count auto)))
               (row "<span class=\"ok\">escalated &rarr; human-approved commits</span>" (n-cell (count approved)))
               (row "<span class=\"critical\">HARD governor holds (never reach a human)</span>" (n-cell (count hs)))
               (row "distinct HARD rules exercised" (n-cell (count rules)))
               (row "committed facts in the audit ledger" (n-cell (count committed)))
               (row "audit-ledger facts total" (n-cell (count ledger)))
               (row "numbers provisioned" (n-cell (count (store/provisioning-history db))))
               (row "billing records suppressed" (n-cell (count (store/suppression-history db))))
               (row "confidence floor (<code>governor/confidence-floor</code>)" (n-cell governor/confidence-floor))
               (row "jurisdictions with an official spec-basis"
                    (n-cell (str (:covered cov) " / " (:requested cov))))])

     (section "Line directory"
              "The SSoT after the run. <code>e164-number</code> and the dispute flag are the ground
               truth the Telecom Access Governor re-checks independently &mdash; never the advisor's
               own confidence. The two actuation booleans are dedicated facts, never a
               <code>:status</code> value, so the double-actuation guards cannot be confused by a
               lifecycle change."
              ["Id" "Holder" "E.164 number" "Jurisdiction" "Status" "Billing dispute"
               "Provisioned?" "Provisioning no." "Suppressed?" "Suppression no."]
              (line-rows db))

     (section "Operation dispositions (this run)"
              "One row per graph run. The outcome and the hold reason are classified from each run's
               own audit trail; the detail text is the governor's own message, verbatim. Where a row
               reads &ldquo;approved by&rdquo;, that approver comes from the run's
               <code>:approval-granted</code> audit fact &mdash; see the next section for which
               registers actually retain it."
              ["Thread" "Op" "Subject" "Jurisdiction" "Outcome" "Governor detail"]
              (run-rows db runs))

     (attribution-section att)

     (section "Action gate (Telecom Access Governor)"
              "Derived from <code>phase/write-ops</code>, <code>phase/phases</code> and
               <code>governor/high-stakes</code> &mdash; if the code changes, this table changes.
               All six governor checks are HARD: a human approver cannot override them. The
               confidence/actuation gate is SOFT (it asks a human to look), but
               <code>:actuation/provision-number</code> and
               <code>:actuation/suppress-billing-record</code> are ALSO absent from every phase's
               auto set, so two independent layers agree that actuation is always a human call."
              ["Op" "Writable from phase" "At phase 3" "Permanent escalation"]
              (gate-rows))

     (section "Rollout phase ladder"
              "Read straight out of <code>telecom.phase/phases</code>."
              ["Phase" "Label" "Writes allowed" "May auto-commit"]
              (phase-rows))

     (section "Numbering-plan spec-basis catalog"
              "Read straight out of <code>telecom.facts/catalog</code>. This is a STARTING catalog,
               not a survey of all ~194 jurisdictions: a jurisdiction absent from this table has NO
               spec-basis, and the governor holds any identity-verification proposal against it
               rather than letting the advisor invent that jurisdiction's requirements."
              ["ISO3" "Jurisdiction" "Owner authority" "Legal basis" "Official source" "Required evidence"]
              (spec-basis-rows))

     (section "Identity-verification register"
              "Committed <code>:verification/set</code> payloads. &ldquo;Evidence satisfied&rdquo; is
               recomputed here through <code>facts/required-evidence-satisfied?</code> &mdash; the same
               function the governor's <code>:evidence-incomplete</code> check calls &mdash; rather
               than copied from the record."
              ["Line" "Jurisdiction" "Checklist items" "Evidence satisfied?" "Spec basis" "Approved by"]
              (verification-rows db))

     (section "Billing-dispute screening register"
              "Committed <code>:billing-screen/set</code> payloads. A verdict of
               <code>:unresolved</code> is a HARD, un-overridable hold, so an unresolved screening
               never appears here &mdash; it is held before it can commit."
              ["Line" "Verdict" "Approved by"]
              (screening-rows db))

     (section "Audit ledger"
              "Append-only decision facts the run actually wrote to the store."
              ["Fact" "Op" "Subject" "Disposition" "Basis / violated rule" "Summary"]
              (ledger-rows db))

     (section "Number-provisioning drafts"
              "Jurisdiction-scoped sequence numbers built by <code>telecom.registry</code>. Every
               certificate this actor produces is UNSIGNED (<code>status
               &quot;draft-unsigned&quot;</code>, <code>issued_by_registry false</code>) &mdash;
               signature is the operator's own act, not this actor's."
              ["Record id" "Kind" "Line" "Jurisdiction" "Immutable"]
              (artifact-rows (store/provisioning-history db) "line_id"))

     (section "Billing-suppression drafts"
              "The SECOND actuation lifecycle, on the same entity. This is a NEGATIVE act &mdash;
               withholding/silencing a billing record, not issuing one &mdash; which is why it carries
               its own sequence counter and its own double-actuation guard rather than sharing the
               provisioning one."
              ["Record id" "Kind" "Line" "Jurisdiction" "Immutable"]
              (artifact-rows (store/suppression-history db) "line_id"))

     "</main>\n"
     "<footer>\n"
     "  <p>This actor NEVER provisions a real E.164 number and NEVER suppresses a real billing record\n"
     "  on its own authority &mdash; <code>telecom.registry</code> is pure and touches no telecom\n"
     "  switch, HLR, number-portability database or billing system. It builds the RECORD an operator\n"
     "  would keep. Committing one means a draft was logged, never that a number went live or a\n"
     "  customer's billing record was silenced.</p>\n"
     "  <p>Regenerate: <code>clojure -M:dev:render-html</code></p>\n"
     "</footer>\n"
     "</body></html>\n")))

;; ----------------------------- entry point -----------------------------

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs] :as result} (run-demo!)
        hs (holds db)
        commits (filterv #(= :committed (:t %)) (store/ledger db))]
    ;; A console that shows no real HARD hold is not evidence of a governor.
    ;; This is a build-time INVARIANT, not a convention: the page is never
    ;; written unless the run really produced a hold.
    (when (empty? hs)
      (throw (ex-info "no :governor-hold fact on the ledger — refusing to write a console that shows no real hold"
                      {:ledger-facts (count (store/ledger db))})))
    ;; ...and one that shows no commit at all is not evidence of an actor.
    (when (empty? commits)
      (throw (ex-info "no :committed fact on the ledger — refusing to write a console that shows no clean path"
                      {:ledger-facts (count (store/ledger db))})))
    (let [f (java.io.File. ^String out)]
      (when-let [p (.getParentFile f)] (.mkdirs p))
      (spit f (render result)))
    (println "wrote" out
             (str "(" (count (store/ledger db)) " ledger facts, "
                  (count hs) " HARD holds, "
                  (count (into #{} (mapcat (fn [h] (map :rule (:violations h))) hs))) " distinct HARD rules, "
                  (count commits) " commits, "
                  (count runs) " requests, "
                  (count (store/provisioning-history db)) " number provisionings, "
                  (count (store/suppression-history db)) " billing suppressions)"))))
