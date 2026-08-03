package dev.iiahmed.lowbyte.api.rewrite

import dev.iiahmed.lowbyte.api.RuntimeRewrite

/**
 * `Arrays.mismatch`, twenty overloads of it.
 *
 * The one the other three are built on, and the only one here doing real work:
 * a loop over two ranges looking for the first element that differs. Eight
 * primitive types, references by `Objects.equals`, and references under a
 * comparator, each in a whole-array and a range form.
 */
object ArraysMismatchRewrite : RuntimeRewrite("arraysMismatch") {
    override val name = "Arrays.mismatch"
}

/**
 * `Arrays.equals`, the range forms and the two comparator ones.
 *
 * The whole-array primitive overloads are not here: Java 8 already had those,
 * so there is nothing to replace. What Java 9 added is comparing two *ranges*,
 * which the older `equals` cannot express without copying.
 */
object ArraysEqualsRewrite : RuntimeRewrite("arraysEquals") {
    override val name = "Arrays.equals"
}

/**
 * `Arrays.compare`, a lexicographic order over arrays.
 *
 * Java 8 has no ordering for arrays at all, only equality, so this is a genuine
 * addition rather than a range form of something older.
 */
object ArraysCompareRewrite : RuntimeRewrite("arraysCompare") {
    override val name = "Arrays.compare"
}

/**
 * `Arrays.compareUnsigned`, the same order reading the elements as unsigned.
 *
 * Only the four integral types have one, since unsigned means nothing for the
 * rest.
 */
object ArraysCompareUnsignedRewrite : RuntimeRewrite("arraysCompareUnsigned") {
    override val name = "Arrays.compareUnsigned"
}
