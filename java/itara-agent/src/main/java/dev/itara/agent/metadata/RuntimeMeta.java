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

    private String language = "";
    private String compiler = "";

    /** @return the implementation language, e.g. "java" */
    public String getLanguage() { return language; }
    /** @param language the implementation language, e.g. "java" */
    public void setLanguage(String language) { this.language = language; }

    /** @return the compiler/language version used to build this artifact */
    public String getCompiler() { return compiler; }
    /** @param compiler the compiler/language version used to build this artifact */
    public void setCompiler(String compiler) { this.compiler = compiler; }

    @Override
    public String toString() {
        return "RuntimeMeta{language='" + language + "', compiler='" + compiler + "'}";
    }
}
