"""Guard the HLS rendition-switch fix against post-prepare seek regressions."""
from pathlib import Path

root = Path(__file__).resolve().parent.parent
source = (root / "app/src/main/java/xiao/bu/tv/MainActivity.java").read_text(encoding="utf-8")

start = source.index("    private void selectVideoTrack(")
end = source.index("    private static void restoreTrackProgress(", start)
method = source[start:end]

assert "boolean byteRangePlaylist=proxy.usesByteRangeMediaPlaylist();" in method
assert "long initialPosition=byteRangePlaylist?0L:resume;" in method
assert "startIjkPlayer(channel,source,software,direct,null,initialPosition);" in method
assert "startPlayer(channel,source);" not in method
assert "trackResumePosition=0L" in method

recovery_start = source.index("    private void scheduleMediaTrackRecovery(")
recovery_end = source.index("    private void rememberVideoTrack(", recovery_start)
recovery = source[recovery_start:recovery_end]
assert "startIjkPlayer(channel, url, software, direct, tracks, resume);" in recovery
assert "trackResumePosition = 0L" in recovery

print("PASS HLS track switches use seek-at-start and avoid post-prepare seek")
