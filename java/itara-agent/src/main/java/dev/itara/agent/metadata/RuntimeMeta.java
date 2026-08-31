package dev.itara.agent.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The optional [runtime] section of a `.itara` metadata file.
 *
 * <pre>{@code
 * [runtime]
 * language = "java"
 * compiler = "21"
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RuntimeMeta {

    /** Required for deserialization. */
    public RuntimeMeta() {}

    private String language = "";
    private String compiler = "";

    /**
     * Returns the implementation language, e.g. "java".
     *
     * @return the implementation language, e.g. "java"
     */
    public String getLanguage() { return language; }
    /**
     * Sets the implementation language, e.g. "java".
     *
     * @param language the implementation language, e.g. "java"
     */
    public void setLanguage(String language) { this.language = language; }

    /**
     * Returns the compiler/language version used to build this artifact.
     *
     * @return the compiler/language version used to build this artifact
     */
    public String getCompiler() { return compiler; }
    /**
     * Sets the compiler/language version used to build this artifact.
     *
     * @param compiler the compiler/language version used to build this artifact
     */
    public void setCompiler(String compiler) { this.compiler = compiler; }

    @Override
    public String toString() {
        return "RuntimeMeta{language='" + language + "', compiler='" + compiler + "'}";
    }
}
