package dev.steelaspect.cytrabackups.core.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The only accepted values of a string setting (checked on load and offered as a cycle button in the GUI). */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Choices {
	String[] value();
}
