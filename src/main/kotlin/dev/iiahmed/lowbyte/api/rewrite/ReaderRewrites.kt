package dev.iiahmed.lowbyte.api.rewrite

import dev.iiahmed.lowbyte.api.RuntimeRewrite

/**
 * `Reader.transferTo`, a loop over `read` and `write`.
 *
 * `Reader` is not final, so this forwards a virtual call to a static one and an
 * override would be bypassed. That is what ruled out `InputStream.readAllBytes`
 * and friends, which `ByteArrayInputStream` and `FileInputStream` really do
 * override. No JDK `Reader` overrides `transferTo`, so the same objection does
 * not land here. See APIs.md.
 *
 * Matches a receiver declared as `Reader`. Declared as `BufferedReader` the
 * owner differs and the call is reported instead.
 */
object ReaderTransferToRewrite : RuntimeRewrite("transferTo") {
    override val name = "Reader.transferTo"
}
