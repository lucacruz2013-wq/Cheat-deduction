package com.cheatdeduction.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.OptionalDouble;
import net.minecraft.client.renderer.RenderType;
import org.lwjgl.opengl.GL11;

/**
 * RenderType de linhas opacas (alpha 1.0) para o X-Ray do cheat MINING.
 *
 * ATENÇÃO: DepthTestStateShard com GL_ALWAYS (519) é um no-op no setup/clear do vanilla
 * (ele simplesmente não mexe no estado). Por isso o teste de profundidade é desligado
 * explicitamente em ClientEvents (RenderSystem.disableDepthTest + depthMask(false)) ao redor
 * do endBatch, que é quando as linhas são realmente desenhadas.
 */
public final class XrayRenderTypes extends RenderType {

    private XrayRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int bufSize,
                            boolean affectsCrumbling, boolean sortOnUpload, Runnable setup, Runnable clear) {
        super(name, format, mode, bufSize, affectsCrumbling, sortOnUpload, setup, clear);
    }

    private static final DepthTestStateShard ALWAYS_DEPTH = new DepthTestStateShard("always", GL11.GL_ALWAYS);

    public static final RenderType LINES_NO_DEPTH = create(
            "cheatdeduction_xray_lines",
            DefaultVertexFormat.POSITION_COLOR_NORMAL,
            VertexFormat.Mode.LINES,
            256, false, false,
            RenderType.CompositeState.builder()
                    .setShaderState(RENDERTYPE_LINES_SHADER)
                    .setLineState(new LineStateShard(OptionalDouble.of(3.0D)))
                    .setLayeringState(NO_LAYERING)
                    .setTransparencyState(NO_TRANSPARENCY)
                    .setWriteMaskState(COLOR_WRITE)
                    .setCullState(NO_CULL)
                    .setDepthTestState(ALWAYS_DEPTH)
                    .createCompositeState(false));
}
