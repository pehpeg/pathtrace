package com.pathtrace.client;

import org.apache.commons.io.IOUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

final class ShaderProgram {
    private static final String DIR = "/assets/pathtrace/shaders/";

    private final int id;
    private final Map<String, Integer> locations = new HashMap<>();

    private ShaderProgram(int id) {
        this.id = id;
    }

    static ShaderProgram load(String vertex, String fragment) throws IOException {
        int vs = compile(GL20.GL_VERTEX_SHADER, read(vertex), vertex);
        int fs;
        try {
            fs = compile(GL20.GL_FRAGMENT_SHADER, read(fragment), fragment);
        } catch (IOException | RuntimeException e) {
            GL20.glDeleteShader(vs);
            throw e;
        }
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vs);
        GL20.glAttachShader(program, fs);
        GL20.glLinkProgram(program);
        GL20.glDetachShader(program, vs);
        GL20.glDetachShader(program, fs);
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(program, 8192);
            GL20.glDeleteProgram(program);
            throw new IOException("Link failed (" + fragment + "):\n" + log);
        }
        return new ShaderProgram(program);
    }

    /** Reads a shader, expanding lines of the form: #include "file.glsl" */
    private static String read(String name) throws IOException {
        String src;
        try (InputStream in = ShaderProgram.class.getResourceAsStream(DIR + name)) {
            if (in == null) throw new IOException("Missing shader " + name);
            src = IOUtils.toString(in, StandardCharsets.UTF_8);
        }
        StringBuilder out = new StringBuilder(src.length() + 4096);
        for (String line : src.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("#include")) {
                int a = t.indexOf('"'), b = t.lastIndexOf('"');
                if (a < 0 || b <= a) throw new IOException("Bad include in " + name + ": " + t);
                out.append(read(t.substring(a + 1, b))).append('\n');
            } else {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    private static int compile(int type, String src, String name) throws IOException {
        int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, src);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(s, 8192);
            GL20.glDeleteShader(s);
            throw new IOException("Compile failed (" + name + "):\n" + log);
        }
        return s;
    }

    void use() {
        GL20.glUseProgram(id);
    }

    void delete() {
        GL20.glDeleteProgram(id);
    }

    private int loc(String name) {
        Integer l = locations.get(name);
        if (l == null) {
            l = GL20.glGetUniformLocation(id, name);
            locations.put(name, l);
        }
        return l;
    }

    void set(String name, int v) {
        GL20.glUniform1i(loc(name), v);
    }

    void set(String name, float v) {
        GL20.glUniform1f(loc(name), v);
    }

    void set(String name, float x, float y) {
        GL20.glUniform2f(loc(name), x, y);
    }

    void set(String name, float x, float y, float z) {
        GL20.glUniform3f(loc(name), x, y, z);
    }

    void set(String name, float[] v3) {
        GL20.glUniform3f(loc(name), v3[0], v3[1], v3[2]);
    }

    void setVec4Array(String name, FloatBuffer values) {
        values.rewind();
        GL20.glUniform4(loc(name), values);
        values.rewind();
    }

    void setMatrix(String name, FloatBuffer m) {
        m.rewind();
        GL20.glUniformMatrix4(loc(name), false, m);
        m.rewind();
    }
}
