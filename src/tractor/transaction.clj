(ns tractor.transaction)

(def ^{:dynamic true} *transactions* #{})
(def ^{:dynamic true} *revision*     #{})

(defprotocol Transactional
  (txn-wrap [obj f] "Wrap the given function in a transaction, returning a new function."))

(defprotocol TransactionalSplit
  (txn-begin    [obj] "Begin a new transaction.")
  (txn-commit   [obj] "Commit the current transaction.")
  (txn-rollback [obj] "Rollback the current transaction."))

(defprotocol Revisioned
  (get-revision  [obj]     "Returns the current revision.")
  (set-revision! [obj rev] "Set the current revision to rev."))

(defmacro at-revision
  "Execute the given forms with the curren revision set to rev. Can be used to mark changes with a given
   revision, or read the state at a given revision."
  [rev & forms]
  `(binding [*revision* ~rev]
     ~@forms))

(defn- skip-past-revisions
  "Wraps the given function in a new function that skips it if the current revision has already
   been applied, also setting the revision upon executing the function."
  [layer f]
  (fn []
    (if-not layer/*rev*
      (f)
      (let [rev (or (get-revision obj) 0)]
        (if (<= *revision* rev)
          (printf "skipping revision: revision [%s] <= current revision [%s]\n" layer/*rev* rev)
          (let [result (f)]
            (if *revision*
              (set-revision! obj rev))
            result))))))

(defn- ignore-nested-transactions
  "Takes two functions, one that's wrapped in a transaction and one that's not, returning a new fn
   that calls the transactional fn if not currently in a transaction or otherwise calls the plain fn."
  [obj f f-txn]
  (fn []
    (if (contains? *transactions* obj)
      (f)
      (binding [*transactions* (conj *transactions* obj)]
        (f-txn)))))

(defn- catch-rollbacks
  "Takes a function and wraps it in a new function that catches the exception thrown by abort-transaction."
  [f]
  (fn []
    (try (f)
         (catch javax.transaction.TransactionRolledbackException e))))

(defn- wrap-txn-split [obj f]
  (fn []
    (txn-begin obj)
    (try (let [result (f)]
           (txn-commit obj)
           result)
         (catch Throwable e
           (txn-abort obj)
           (throw e)))))

(defn wrap-transaction
  "Takes a function and returns a new function wrapped in a transaction on the given object."
  [obj f]
  (->> (if (satisfies? Transactional obj)
         (wrap-txn obj f)
         (wrap-txn-split obj f))
       (catch-rollbacks)
       (skip-past-revisions obj)
       (ignore-nested-transactions obj f)))

(defmacro with-transaction
  "Execute forms withing a transaction on the named layer."
  [obj & forms]
  `((wrap-transaction ~obj (fn [] ~@forms))))

(defn abort-transaction
  "Throws an exception that will be caught by catch-rollbacks to abort the transaction."
  []
  (throw (javax.transaction.TransactionRolledbackException.)))
