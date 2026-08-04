package dev.iiahmed.lowbyte.runtime;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Says which JDK call a utility method stands in for.
 *
 * The plugin reads this off the compiled class, so the utility describes itself
 * and there is no second list to keep in step with it.
 *
 * Never shipped. The annotation is stripped from the class before it is written
 * into anybody's jar, and this type is not injected at all.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface LowbyteInfo {

    /** Internal name of the type declaring the call, e.g. {@code java/lang/String}. */
    String owner();

    /** The call's name. */
    String name();

    /** The call's descriptor, as it appears at the call site. */
    String descriptor();

    /** The release the call arrived in. Below this target, it is replaced. */
    int introducedIn();
}
