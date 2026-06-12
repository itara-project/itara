package io.itara.agent.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The optional [runtime] section of a `.itara` metadata file.
 *
 *   [runtime]
 *   language = "java"
 *   compiler = "21"
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RuntimeMeta {

    private String language = "";
    private String compiler = "";

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public String getCompiler() { return compiler; }
    public void setCompiler(String compiler) { this.compiler = compiler; }

    @Override
    public String toString() {
        return "RuntimeMeta{language='" + language + "', compiler='" + compiler + "'}";
    }
}
