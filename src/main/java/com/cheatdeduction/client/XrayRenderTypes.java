package com.cheatdeduction.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.OptionalDouble;
import net.minecraft.client.renderer.RenderType;
import org.lwjgl.opengl.GL11;

/**
 * RenderType de linhas que ignora a profundidade (DepthTest GL_ALWAYS) e é opaco (alpha 1.0),
 * desenhando contornos através de todas as paredes.
 * Estende RenderType apenas para acessar os RenderStateShard protegidos.
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
                    .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                    .setTransparencyState(NO_TRANSPARENCY)
                    .setWriteMaskState(COLOR_WRITE)
                    .setCullState(NO_CULL)
                    .setDepthTestState(ALWAYS_DEPTH)
                    .createCompositeState(false));
}
