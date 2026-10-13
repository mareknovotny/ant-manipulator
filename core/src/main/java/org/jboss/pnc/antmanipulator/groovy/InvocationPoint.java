package org.jboss.pnc.antmanipulator.groovy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares when a Groovy manipulation script should be invoked.
 *
 * <p>
 * Mirrors {@code org.jboss.pnc.mavenmanipulator.core.groovy.InvocationPoint} from pom-manipulation-ext.
 * Every script annotated with {@link AMEBaseScript} must carry this annotation.
 *
 * <pre>
 * import org.jboss.pnc.antmanipulator.groovy.AMEBaseScript
 * import org.jboss.pnc.antmanipulator.groovy.BaseScript
 * import org.jboss.pnc.antmanipulator.groovy.InvocationPoint
 * import org.jboss.pnc.antmanipulator.groovy.InvocationStage
 *
 * &#64;InvocationPoint(invocationPoint = InvocationStage.FIRST)
 * &#64;AMEBaseScript BaseScript ame
 *
 * ame.getLogger().info("Base dir: {}", ame.getBaseDir())
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.LOCAL_VARIABLE, ElementType.TYPE })
public @interface InvocationPoint {

    InvocationStage invocationPoint();
}
