package dev.iiahmed.lowbyte.api

/**
 * What the API check needs to know, present only when it was asked for.
 *
 * The two halves lean on different things. Rebuilding a call needs the release
 * alone, since each rebuild knows which release its API arrived in. Reporting
 * one needs [index], because the question there is open-ended: anything in the
 * JDK might be missing, and only `ct.sym` knows what was there.
 *
 * So [index] may be empty while [targetJava] still does useful work, which is
 * what happens on a JDK that ships no usable `ct.sym`: the rebuilds carry on and
 * only the reporting goes quiet.
 */
class ApiSettings(val targetJava: Int, val index: ApiIndex)
