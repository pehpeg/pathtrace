package com.pathtrace.client;

import com.pathtrace.PathTraceMod;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/** A framebuffer with one or more float colour attachments (multiple render targets). */
final class RenderTarget {
    final int width, height, fbo;
    final int[] tex;

    RenderTarget(int width, int height, int attachments, int minFilter) {
        this.width = width;
        this.height = height;
        this.tex = new int[attachments];
        IntBuffer drawBuffers = BufferUtils.createIntBuffer(attachments);
        fbo = OpenGlHelper.glGenFramebuffers();
        OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, fbo);
        for (int i = 0; i < attachments; i++) {
            tex[i] = GlStateManager.generateTexture();
            GlStateManager.bindTexture(tex[i]);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, minFilter);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, width, height, 0,
                    GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer) null);
            OpenGlHelper.glFramebufferTexture2D(OpenGlHelper.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0 + i,
                    GL11.GL_TEXTURE_2D, tex[i], 0);
            drawBuffers.put(GL30.GL_COLOR_ATTACHMENT0 + i);
        }
        drawBuffers.flip();
        GL20.glDrawBuffers(drawBuffers);
        int status = OpenGlHelper.glCheckFramebufferStatus(OpenGlHelper.GL_FRAMEBUFFER);
        if (status != OpenGlHelper.GL_FRAMEBUFFER_COMPLETE) {
            PathTraceMod.LOG.error("PathTrace framebuffer incomplete: 0x" + Integer.toHexString(status));
        }
        GlStateManager.clearColor(0f, 0f, 0f, 0f);
        GlStateManager.clear(GL11.GL_COLOR_BUFFER_BIT);
    }

    void bind() {
        OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, fbo);
        GlStateManager.viewport(0, 0, width, height);
    }

    void delete() {
        OpenGlHelper.glDeleteFramebuffers(fbo);
        for (int t : tex) GlStateManager.deleteTexture(t);
    }
}
