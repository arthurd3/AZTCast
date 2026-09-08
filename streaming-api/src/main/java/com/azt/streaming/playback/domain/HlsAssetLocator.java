package com.azt.streaming.playback.domain;

import org.springframework.core.io.Resource;

/** Looks up the bytes behind an HLS request. */
public interface HlsAssetLocator {

    /**
     * @throws AssetNotFoundException if the asset is missing or outside the media root
     */
    Resource locate(String videoId, String fileName);
}
