This note describes the lifecycle of a query in Hail Query.

As a user builds a query, the Python front-end constructs an IR
representation of the query that will be sent to the backend for
execution.

```
>>> import hail as hl
>>> t = hl.utils.range_table(100)
```

Python objects that represent (possibly partial) computations
(expression, table, matrix table or block matrix) carry an IR that
implements the computation.  The IR can be printed like this:

```
>>> t = hl.utils.range_table(100)
>>> print(t._tir)
(TableRange 100 None)
```

The IR is stored as `_ir` for expressions, `_mir` for matrix tables, and `_bmir` for
block matrices.

Let's filter `t`.  Here's another example:

```
>>> c = t.idx % 7 < 4
>>> print(c._ir)
(ApplyComparisonOp `<`  (Apply mod () Int32  (GetField idx  (Ref row)) (I32 7)) (I32 4))
>>> t = t.filter(c)
>>> print(t._tir)
(TableFilter  (TableRange 100 None) (Coalesce  (ApplyComparisonOp `<`  (Apply mod () Int32  (GetField idx  (Ref row)) (I32 7)) (I32 4)) (False)))
```

In the repo, the Python implementation of the IR lives in
$HAIL/hail/python/hail/ir.

Next, suppose we perform an operation that requires the lazy pipeline
to be executed, `Table.count`, say.  Here's the implementation:

```
    def count(self):
        return Env.backend().execute(ir.TableCount(self._tir))
```

The IR is sent to the backend to execute.  There are three backends in
Python, each implementing the abstract base class
hail.backend.Backend:
 - SparkBackend,
 - LocalBackend,
 - ServiceBackend.

The Python Spark and local backends are implemented by a JVM running
in parallel with Python.  The backends on the JVM are implemented by
classes extending is.hail.backend.Backend.  These backends works by
calling into the JVM backends via Py4J (soon to be replaced with a
unix domain socket).

The Python service backend connects to the Hail Query service REST API
to execute pipelines.  The query service is implemented in Python but
again has a parallel JVM with a ServiceBackend that does the heavy
lifting.

When invoked from Python, the JVM backends perform the following:
 - The IR is serialized as a string, and it is parsed by IRParser.
 - The IR is type checked, see `TypeCheck`.
 - The IR is optimized and lowered.  There are a few versions of this,
   but the full version looks like:
   - Lower MatrixTable IR in terms of TableIR.  All MatrixTable IR are
     now eliminated.  See `LowerMatrixIR`.
   - Lower TableIR and BlockMatrixIR to expressions and a
     CollectDistributedArray IR which represents the execution of a
     stage of the pipeline.  See `LowerTableIR`.
   - Shuffles (distributed sorts) are implemented on a per-backend
     basis by `Backend.lowerDistributedSort`. A new distribution-sort
     implementation of a distributed sort is in progress.
 - The IR is optimized after parsing and after each lowering step.
 - JVM bytecode is generated for the lowered, optimized IR.  See
   `Emit`.  Again, each backend invokes the generated bytecode
   differently:
    - For all pipelines, code not inside of a parallel operation
      (collecting a distributed array) is executed in the driver.
    - The Spark backend executes code on Spark workers by wrapping it
      in an RDD.
    - The local backend executes everything locally.
    - The service backend serializes the generated code to GCS and
      submits a Hail Batch to execute that code on Batch workers.
 - The final result is serialized and sent back to the Python caller
   which is then returned to the user.

Lowering is a work in progress, so not all pipelines run on the local
and service backends.  The Spark backend has a second, legacy
execution strategy which lowers MatrixIR to TableIR, but then
interprets the TableIR by calling `TableIR.execute` rather than
lowering to CollectDistributedArray.  When lowering is feature
complete, `TableIR.execute` will be removed.
