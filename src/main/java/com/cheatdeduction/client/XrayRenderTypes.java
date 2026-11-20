package com.cheatdeduction.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.OptionalDouble;
import net.minecraft.client.renderer.RenderType;

/**
 * RenderType de linhas SEM teste de profundidade (enxerga através das paredes).
 * Estende RenderType apenas para acessar os RenderStateShard protegidos.
 */
public final class XrayRenderTypes extends RenderType {

    private XrayRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int bufSize,
                            boolean affectsCrumbling, boolean sortOnUpload, Runnable setup, Runnable clear) {
        super(name, format, mode, bufSize, affectsCrumbling, sortOnUpload, setup, clear);
    }

    public static final RenderType LINES_NO_DEPTH = create(
            "cheatdeduction_xray_lines",
            DefaultVertexFormat.POSITION_COLOR_NORMAL,
            VertexFormat.Mode.LINES,
            256, false, false,
            RenderType.CompositeState.builder()
                    .setShaderState(RENDERTYPE_LINES_SHADER)
                    .setLineState(new LineStateShard(OptionalDouble.of(2.5D)))
                    .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setWriteMaskState(COLOR_WRITE)
                    .setCullState(NO_CULL)
                    .setDepthTestState(NO_DEPTH_TEST)
                    .createCompositeState(false));
}
