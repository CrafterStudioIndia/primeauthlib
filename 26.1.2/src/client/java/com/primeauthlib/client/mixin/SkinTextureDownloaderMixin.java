package com.primeauthlib.client.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SkinTextureDownloader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Mixin into net.minecraft.client.renderer.texture.SkinTextureDownloader.
 * Directly intercepts local 'file://' skin texture URLs, loading them straight from disk
 * without making HTTP connections or failing on protocol casts.
 */
@Mixin(value = SkinTextureDownloader.class, priority = 1500)
public class SkinTextureDownloaderMixin {
	@Inject(method = "downloadSkin", at = @At("HEAD"), cancellable = true)
	private void primeauthlib$onDownloadSkin(Path cachePath, String url, CallbackInfoReturnable<NativeImage> cir) {
		if (url != null && url.startsWith("file:")) {
			try {
				Path filePath = Path.of(URI.create(url));
				if (Files.exists(filePath)) {
					try (InputStream is = Files.newInputStream(filePath)) {
						NativeImage image = NativeImage.read(is);
						cir.setReturnValue(image);
					}
				}
			} catch (Exception ignored) {
				// Fall through to vanilla logic
			}
		}
	}
}
