package com.azt.streaming.playback.domain;

/** Looks up the file behind an HLS request. */
public interface HlsAssetLocator {

    /**
     * @throws AssetNotFoundException if the asset is missing or outside the media root
     */
    HlsAsset locate(String videoId, String fileName);
}
