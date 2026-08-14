package com.hhst.youtubelite.extractor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.hhst.youtubelite.extractor.potoken.LitePoTokenProvider;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.Description;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Contract for fetching extracted playback data by video id.
 */
@FunctionalInterface
interface Fetch {
	ExtractedInfo fetch(@NonNull String videoId,
	                    @Nullable ExtractionSession session)
					throws org.schabi.newpipe.extractor.exceptions.ExtractionException, IOException;
}

/**
 * Coordinates YouTube extraction, caching, and playback-plan assembly.
 */
@Singleton
public final class YoutubeExtractor {
	@NonNull
	private final Fetch play;
	@NonNull
	private final Fetch info;
	@NonNull
	private final InfoCache cache;
	@NonNull
	private final Executor executor;
	@NonNull
	private final Gson gson;
	@NonNull
	private final AuthContextFactory auth;
	@NonNull
	private final ConcurrentMap<String, Task> tasks = new ConcurrentHashMap<>();

	@Inject
	public YoutubeExtractor(@NonNull DownloaderImpl downloader,
	                        @NonNull LitePoTokenProvider litePoTokenProvider,
	                        @NonNull AuthContextFactory auth,
	                        @NonNull SessionClientProfileProvider profiles,
	                        @NonNull InfoCache cache,
	                        @NonNull Executor executor,
	                        @NonNull Gson gson) {
		this(
						(videoId, session) -> downloader.withExtractionSession(
										() -> extract(
														"https://www.youtube.com/watch?v=" + videoId,
														true),
										session),
						(videoId, session) -> downloader.withExtractionSession(
										() -> extract(
														"https://www.youtube.com/watch?v=" + videoId,
														false),
										session),
						cache,
						executor,
						gson,
						auth);
		NewPipe.init(downloader);
		YoutubeStreamExtractor.setPoTokenProvider(litePoTokenProvider);
		YoutubeStreamExtractor.setClientProfileProvider(profiles);
	}

	YoutubeExtractor(@NonNull Fetch play,
	                 @NonNull Fetch info,
	                 @NonNull InfoCache cache,
	                 @NonNull Executor executor,
	                 @NonNull Gson gson,
	                 @NonNull AuthContextFactory auth) {
		this.play = play;
		this.info = info;
		this.cache = cache;
		this.executor = executor;
		this.gson = gson;
		this.auth = auth;
	}

	@Nullable
	public static String getVideoId(@Nullable String url) {
		if (url == null) return null;

		Pattern compiledPattern = Pattern.compile("(?:v=|=v/|/v/|/u/\\w/|embed/|watch\\?v=|shorts/|youtu.be/)([a-zA-Z0-9_-]{11})");
		Matcher matcher = compiledPattern.matcher(url);

		if (matcher.find()) {
			return matcher.group(1);
		}
		return null;
	}

	private static boolean same(@Nullable Object first,
	                            @Nullable Object second) {
		return Objects.equals(first, second);
	}

	private static boolean isLive(@NonNull org.schabi.newpipe.extractor.stream.StreamType streamType) {
		return streamType == org.schabi.newpipe.extractor.stream.StreamType.LIVE_STREAM
						|| streamType == org.schabi.newpipe.extractor.stream.StreamType.AUDIO_LIVE_STREAM
						|| streamType == org.schabi.newpipe.extractor.stream.StreamType.POST_LIVE_STREAM;
	}

	@Nullable
	private static <T> List<T> copyList(@Nullable List<T> source) {
		return source == null ? null : new ArrayList<>(source);
	}

	@NonNull
	private static <T> List<T> orEmpty(@Nullable List<T> source) {
		return source == null ? Collections.emptyList() : source;
	}

	@NonNull
	private static <T> CompletableFuture<T> fail(@NonNull Throwable error) {
		CompletableFuture<T> future = new CompletableFuture<>();
		future.completeExceptionally(error);
		return future;
	}

	@NonNull
	private static ExtractedInfo extract(@NonNull String url,
	                                     boolean streams)
					throws org.schabi.newpipe.extractor.exceptions.ExtractionException, IOException {
		var extractor = ServiceList.YouTube.getStreamExtractor(url);
		YoutubeStreamExtractor youtube = extractor instanceof YoutubeStreamExtractor y ? y : null;
		StreamInfo info = streams
						? StreamInfo.getStream(extractor)
						: StreamInfo.getInfo(extractor);
		return new ExtractedInfo(info, youtube);
	}

	@NonNull
	public CompletableFuture<PlaybackDetails> getInfo(@NonNull String videoUrl,
	                                                  @Nullable ExtractionSession session) {
		String videoId = getVideoId(videoUrl);
		if (videoId == null) {
			return fail(new org.schabi.newpipe.extractor.exceptions.ExtractionException(
							"Invalid URL: " + videoUrl));
		}
		if (session != null && session.isCancelled()) {
			return fail(new InterruptedException("Extraction canceled"));
		}
		Task task = tasks.compute(videoId, (key, active) -> {
			if (active != null && !active.base.isDone() && !active.root.isCancelled()) {
				return active;
			}
			return new Task(videoId);
		});
		return task.attach(session);
	}

	@NonNull
	private PlaybackDetails load(@NonNull String videoId,
	                             @NonNull ExtractionSession session)
					throws org.schabi.newpipe.extractor.exceptions.ExtractionException,
					IOException,
					InterruptedException {
		ensureNotCancelled(session);

		PlaybackDetails cached = cache.getPlaybackDetails(videoId);
		if (cached != null) {
			return copy(cached, PlaybackDetails.class);
		}

		VideoDetails longVideo = cache.getVideoDetails(videoId);
		if (longVideo != null) {
			try {
				ExtractedInfo extracted = play.fetch(videoId, session);
				StreamInfo streamInfo = extracted.info();
				ensureNotCancelled(session);
				StreamCatalog catalog = buildCatalog(streamInfo, extracted.youtube());
				DeliveryCatalog deliveries = buildDeliveries(catalog);
				PlaybackPlan plan = PlaybackPlanner.plan(deliveries);
				PlaybackDetails details = new PlaybackDetails(
								mergeVideo(longVideo, streamInfo),
								catalog,
								deliveries,
								plan,
								copyList(orEmpty(streamInfo.getStreamSegments())),
								copyList(orEmpty(streamInfo.getSubtitles())));
				ensurePlayableSources(videoId, details.deliveries(), details.plan());
				cache.putPlaybackDetails(videoId, details);
				cache.putVideoDetails(videoId, details.video());
				return copy(details, PlaybackDetails.class);
			} catch (IOException | org.schabi.newpipe.extractor.exceptions.ExtractionException e) {
				ensureNotCancelled(session);
			}
		}

		ExtractedInfo extracted = info.fetch(videoId, session);
		StreamInfo streamInfo = extracted.info();
		ensureNotCancelled(session);
		Description description = streamInfo.getDescription();
		Date uploadDate = streamInfo.getUploadDate() == null
						? null
						: Date.from(streamInfo.getUploadDate().getInstant());
		String thumbnailUrl = getBestImageUrl(streamInfo.getThumbnails());
		StreamCatalog catalog = buildCatalog(streamInfo, extracted.youtube());
		DeliveryCatalog deliveries = buildDeliveries(catalog);
		PlaybackPlan plan = PlaybackPlanner.plan(deliveries);
		PlaybackDetails details = new PlaybackDetails(
						new VideoDetails(
										streamInfo.getId(),
										streamInfo.getName(),
										streamInfo.getUploaderName(),
										description == null ? null : description.getContent(),
										Math.max(0L, streamInfo.getDuration()),
										thumbnailUrl != null ? thumbnailUrl : buildDefaultThumbnailUrl(streamInfo.getId()),
										streamInfo.getLikeCount(),
										streamInfo.getDislikeCount(),
										uploadDate,
										streamInfo.getUploaderUrl(),
										getBestImageUrl(streamInfo.getUploaderAvatars()),
										streamInfo.getViewCount()),
						catalog,
						deliveries,
						plan,
						copyList(orEmpty(streamInfo.getStreamSegments())),
						copyList(orEmpty(streamInfo.getSubtitles())));
		ensurePlayableSources(videoId, details.deliveries(), details.plan());
		cache.putPlaybackDetails(videoId, details);
		cache.putVideoDetails(videoId, details.video());
		return copy(details, PlaybackDetails.class);
	}

	@NonNull
	private VideoDetails mergeVideo(@NonNull VideoDetails cached,
	                                @NonNull StreamInfo streamInfo) {
		VideoDetails details = copy(cached, VideoDetails.class);
		if (isBlank(details.getId())) {
			details.setId(streamInfo.getId());
		}
		if (isBlank(details.getTitle())) {
			details.setTitle(streamInfo.getName());
		}
		if (isBlank(details.getThumbnailUrl())) {
			details.setThumbnailUrl(buildDefaultThumbnailUrl(streamInfo.getId()));
		}
		if (details.getDuration() == null || details.getDuration() < 0L) {
			details.setDuration(Math.max(0L, streamInfo.getDuration()));
		}
		return details;
	}

	@NonNull
	private StreamCatalog buildCatalog(@NonNull StreamInfo streamInfo,
	                                   @Nullable YoutubeStreamExtractor youtube) {
		StreamCatalog catalog = new StreamCatalog();
		catalog.setStreamType(streamInfo.getStreamType());
		boolean live = isLive(streamInfo.getStreamType());

		if (youtube != null) {
			addManifestChoices(catalog, youtube.getDashManifestChoices(), true, live);
			addManifestChoices(catalog, youtube.getHlsManifestChoices(), false, live);
			addVideoChoices(catalog.getVideoCandidates(), youtube.getVideoOnlyStreamChoices(), false, live);
			addAudioChoices(catalog.getAudioCandidates(), youtube.getAudioStreamChoices(), live);
			addVideoChoices(catalog.getMuxedCandidates(), youtube.getMuxedStreamChoices(), true, live);
		}

		if (catalog.getManifestCandidates().isEmpty()) {
			addFallbackManifests(catalog, streamInfo, live);
		}
		if (catalog.getVideoCandidates().isEmpty()) {
			for (VideoStream stream : normalizeVideoStreams(filterPlayableStreams(streamInfo.getVideoOnlyStreams()))) {
				catalog.getVideoCandidates().add(StreamCandidate.videoOnly(stream, null, false, false, live));
			}
		}
		if (catalog.getAudioCandidates().isEmpty()) {
			for (AudioStream stream : normalizeAudioStreams(filterPlayableAudioStreams(streamInfo.getAudioStreams()))) {
				catalog.getAudioCandidates().add(StreamCandidate.audioOnly(stream, null, false, false, live));
			}
		}
		if (catalog.getMuxedCandidates().isEmpty()) {
			for (VideoStream stream : normalizeVideoStreams(filterPlayableStreams(streamInfo.getVideoStreams()))) {
				catalog.getMuxedCandidates().add(StreamCandidate.muxed(stream, null, false, false, live));
			}
		}
		for (SubtitlesStream stream : orEmpty(streamInfo.getSubtitles())) {
			if (isPlayableUrl(stream.getContent())) {
				catalog.getSubtitleCandidates().add(StreamCandidate.subtitle(stream));
			}
		}
		if (!live) {
			preferPoTokenCandidates(catalog);
		}
		logCandidateClients(catalog);
		return catalog;
	}

	/**
	 * Drops candidates whose client produced no streaming PoToken.
	 * <p>
	 * Streams served without a {@code pot} parameter play for roughly a minute
	 * before YouTube starts answering 403 to further range requests, so a
	 * PoToken-backed client is always preferred when one is available. Lists
	 * with no PoToken-backed candidate at all are left untouched, since a
	 * short-lived stream still beats no stream.
	 */
	private static void preferPoTokenCandidates(@NonNull StreamCatalog catalog) {
		retainPoTokenCandidates(catalog.getVideoCandidates());
		retainPoTokenCandidates(catalog.getAudioCandidates());
		retainPoTokenCandidates(catalog.getMuxedCandidates());
		retainPoTokenCandidates(catalog.getManifestCandidates());
	}

	// TODO temporary diagnostic — remove once PoToken selection is confirmed
	private static void logCandidateClients(@NonNull StreamCatalog catalog) {
		android.util.Log.d("STREAM_SELECT", "video=" + describe(catalog.getVideoCandidates())
					+ " audio=" + describe(catalog.getAudioCandidates())
					+ " muxed=" + describe(catalog.getMuxedCandidates()));
	}

	@NonNull
	private static String describe(@NonNull List<StreamCandidate> candidates) {
		StringBuilder builder = new StringBuilder("[");
		for (StreamCandidate candidate : candidates) {
			if (builder.length() > 1) builder.append(',');
			builder.append(candidate.getSourceClient())
						.append(candidate.isStreamPoToken() ? "+pot" : "-pot");
		}
		return builder.append(']').toString();
	}

	private static void retainPoTokenCandidates(@NonNull List<StreamCandidate> candidates) {
		boolean anyWithPoToken = false;
		for (StreamCandidate candidate : candidates) {
			if (candidate.isStreamPoToken()) {
				anyWithPoToken = true;
				break;
			}
		}
		if (anyWithPoToken) {
			candidates.removeIf(candidate -> !candidate.isStreamPoToken());
		}
	}

	@NonNull
	private DeliveryCatalog buildDeliveries(@NonNull StreamCatalog catalog) {
		DeliveryCatalog deliveries = new DeliveryCatalog();
		deliveries.setStreamType(catalog.getStreamType());
		boolean live = isLive(catalog.getStreamType());
		if (live) {
			StreamCandidate dash = catalog.firstDashManifest();
			if (dash != null) {
				Delivery delivery = new Delivery();
				delivery.setMode(PlaybackMode.LIVE_DASH);
				delivery.setStreamType(catalog.getStreamType());
				delivery.setManifest(dash);
				delivery.setVideo(copyList(catalog.getVideoCandidates()));
				delivery.setAudio(copyList(catalog.getAudioCandidates()));
				delivery.setAbr(true);
				delivery.setTrackLock(false);
				delivery.setCache(false);
				deliveries.getItems().add(delivery);
			}
			StreamCandidate hls = catalog.firstHlsManifest();
			if (hls != null) {
				Delivery delivery = new Delivery();
				delivery.setMode(PlaybackMode.LIVE_HLS);
				delivery.setStreamType(catalog.getStreamType());
				delivery.setManifest(hls);
				delivery.setAbr(false);
				delivery.setTrackLock(false);
				delivery.setCache(false);
				deliveries.getItems().add(delivery);
			}
			return deliveries;
		}
		if (!catalog.getVideoCandidates().isEmpty() && !catalog.getAudioCandidates().isEmpty()) {
			Delivery delivery = new Delivery();
			delivery.setMode(PlaybackMode.ADAPTIVE);
			delivery.setStreamType(catalog.getStreamType());
			delivery.setVideo(copyList(catalog.getVideoCandidates()));
			delivery.setAudio(copyList(catalog.getAudioCandidates()));
			delivery.setAbr(false);
			delivery.setTrackLock(false);
			delivery.setCache(true);
			deliveries.getItems().add(delivery);
		}
		if (!catalog.getMuxedCandidates().isEmpty()) {
			Delivery delivery = new Delivery();
			delivery.setMode(PlaybackMode.MUXED);
			delivery.setStreamType(catalog.getStreamType());
			delivery.setMuxed(copyList(catalog.getMuxedCandidates()));
			delivery.setAbr(false);
			delivery.setTrackLock(false);
			delivery.setCache(true);
			deliveries.getItems().add(delivery);
		}
		if (deliveries.getItems().isEmpty() && !catalog.getAudioCandidates().isEmpty()) {
			Delivery delivery = new Delivery();
			delivery.setMode(PlaybackMode.AUDIO_ONLY);
			delivery.setStreamType(catalog.getStreamType());
			delivery.setAudio(copyList(catalog.getAudioCandidates()));
			delivery.setAbr(false);
			delivery.setTrackLock(false);
			delivery.setCache(true);
			deliveries.getItems().add(delivery);
		}
		return deliveries;
	}

	private void addFallbackManifests(@NonNull StreamCatalog catalog,
	                                  @NonNull StreamInfo streamInfo,
	                                  boolean live) {
		String dash = sanitizePlaybackUrl(streamInfo.getDashMpdUrl());
		String hls = sanitizePlaybackUrl(streamInfo.getHlsUrl());
		if (dash != null) {
			catalog.getManifestCandidates().add(StreamCandidate.dashManifest(
							dash,
							null,
							false,
							false,
							live));
		}
		if (hls != null) {
			catalog.getManifestCandidates().add(StreamCandidate.hlsManifest(
							hls,
							null,
							false,
							false,
							live));
		}
	}

	private void addManifestChoices(@NonNull StreamCatalog catalog,
	                                @NonNull List<YoutubeStreamExtractor.ManifestChoice> choices,
	                                boolean dash,
	                                boolean live) {
		for (final YoutubeStreamExtractor.ManifestChoice choice : choices) {
			String url = sanitizePlaybackUrl(choice.getUrl());
			if (url == null) {
				continue;
			}
			StreamCandidate candidate = dash
							? StreamCandidate.dashManifest(
							url,
							choice.getClient(),
							choice.hasPlayerPoToken(),
							choice.hasStreamPoToken(),
							live)
							: StreamCandidate.hlsManifest(
							url,
							choice.getClient(),
							choice.hasPlayerPoToken(),
							choice.hasStreamPoToken(),
							live);
			addUnique(catalog.getManifestCandidates(), candidate);
		}
	}

	private void addVideoChoices(@NonNull List<StreamCandidate> out,
	                             @NonNull List<YoutubeStreamExtractor.ItagChoice<VideoStream>> choices,
	                             boolean muxed,
	                             boolean live) {
		for (final YoutubeStreamExtractor.ItagChoice<VideoStream> choice : choices) {
			for (VideoStream stream : normalizeVideoStreams(choice.getStreams())) {
				StreamCandidate candidate = muxed
								? StreamCandidate.muxed(
								stream,
								choice.getClient(),
								choice.hasPlayerPoToken(),
								choice.hasStreamPoToken(),
								live)
								: StreamCandidate.videoOnly(
								stream,
								choice.getClient(),
								choice.hasPlayerPoToken(),
								choice.hasStreamPoToken(),
								live);
				addUnique(out, candidate);
			}
		}
	}

	private void addAudioChoices(@NonNull List<StreamCandidate> out,
	                             @NonNull List<YoutubeStreamExtractor.ItagChoice<AudioStream>> choices,
	                             boolean live) {
		for (final YoutubeStreamExtractor.ItagChoice<AudioStream> choice : choices) {
			for (AudioStream stream : normalizeAudioStreams(choice.getStreams())) {
				StreamCandidate candidate = StreamCandidate.audioOnly(
								stream,
								choice.getClient(),
								choice.hasPlayerPoToken(),
								choice.hasStreamPoToken(),
								live);
				addUnique(out, candidate);
			}
		}
	}

	private void addUnique(@NonNull List<StreamCandidate> out,
	                       @NonNull StreamCandidate candidate) {
		String url = candidate.getUrl();
		for (StreamCandidate item : out) {
			if (same(item.getKind(), candidate.getKind())
							&& same(item.getSourceClient(), candidate.getSourceClient())
							&& same(item.getUrl(), url)) {
				return;
			}
		}
		out.add(candidate);
	}

	@NonNull
	private List<VideoStream> normalizeVideoStreams(@Nullable List<VideoStream> streams) {
		if (streams == null) return new ArrayList<>();
		Map<String, VideoStream> best = new LinkedHashMap<>();
		for (VideoStream stream : streams) {
			if (stream == null || !isPlayableUrl(stream.getContent())) continue;
			String key = videoKey(stream);
			VideoStream prev = best.get(key);
			
