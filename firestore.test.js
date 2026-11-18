const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";
const DEV_TEST_EMAIL = process.env.SOUNDLAP_DEV_TEST_EMAIL || "";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function ts() {
  return new Date(Date.now() - 2000);
}

function validProfile(uid) {
  return {
    userId: uid,
    displayName: "Alice",
    email: "alice@example.com",
    createdAt: ts(),
    updatedAt: ts(),
  };
}

function validTrack(uid, trackId = "sp_track_1") {
  return {
    userId: uid,
    trackId,
    title: "Yamakasi",
    artist: "Miyagi",
    genre: "Hip-Hop",
    durationMs: 210000,
    sourceUri: "https://example.com/track.mp3",
    coverArtPath: "https://i.scdn.co/image/cover",
    accentColorHex: 0xff10b981,
    secondaryColorHex: 0xff065f46,
    isUserUpload: false,
    isLiked: true,
    playCount: 42,
    likeCount: 1,
    description: "Spotify",
    albumTitle: "Yamakasi",
    trackCreatedAtMs: 1700000000000,
    createdAt: ts(),
    updatedAt: ts(),
  };
}

function validPlaylist(uid, playlistId = "pl_1") {
  return {
    userId: uid,
    playlistId,
    name: "Evening Mix",
    description: "Favorites",
    playlistCreatedAtMs: 1700000000000,
    createdAt: ts(),
    updatedAt: ts(),
  };
}

function validPlaylistItem(uid, playlistId = "pl_1", trackId = "sp_track_1") {
  return {
    userId: uid,
    playlistId,
    trackId,
    position: 0,
    addedAtMs: 1700000000000,
    createdAt: ts(),
    updatedAt: ts(),
  };
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user cannot read or write profiles, tracks, or playlists", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("tracks").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("playlists").get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("tracks").doc("t1").set(validTrack(ALICE_UID, "t1"))
  );
});

test("Owner can create, update, list, and delete their profile, tracks, playlists, and items", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const aliceDoc = aliceDb.collection("users").doc(ALICE_UID);

  await assertSucceeds(aliceDoc.set(validProfile(ALICE_UID)));
  await assertSucceeds(aliceDoc.update({ displayName: "Alice Updated", updatedAt: ts() }));
  await assertSucceeds(aliceDoc.get());

  const trackRef = aliceDoc.collection("tracks").doc("sp_track_1");
  await assertSucceeds(trackRef.set(validTrack(ALICE_UID, "sp_track_1")));
  await assertSucceeds(trackRef.update({ isLiked: false, likeCount: 0, updatedAt: ts() }));
  await assertSucceeds(aliceDoc.collection("tracks").get());

  const plRef = aliceDoc.collection("playlists").doc("pl_1");
  await assertSucceeds(plRef.set(validPlaylist(ALICE_UID, "pl_1")));
  await assertSucceeds(plRef.update({ name: "Renamed Mix", updatedAt: ts() }));

  const itemRef = plRef.collection("items").doc("sp_track_1");
  await assertSucceeds(itemRef.set(validPlaylistItem(ALICE_UID, "pl_1", "sp_track_1")));
  await assertSucceeds(itemRef.update({ position: 2, updatedAt: ts() }));
  await assertSucceeds(plRef.collection("items").get());

  await assertSucceeds(itemRef.delete());
  await assertSucceeds(plRef.delete());
  await assertSucceeds(trackRef.delete());
});

test("Cross-user isolation blocks Bob from reading or writing Alice's data", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("users").doc(ALICE_UID).set(validProfile(ALICE_UID));
    await db.collection("users").doc(ALICE_UID).collection("tracks").doc("sp_track_1").set(validTrack(ALICE_UID));
    await db.collection("users").doc(ALICE_UID).collection("playlists").doc("pl_1").set(validPlaylist(ALICE_UID));
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const aliceRoot = bobDb.collection("users").doc(ALICE_UID);
  await assertFails(aliceRoot.get());
  await assertFails(aliceRoot.collection("tracks").get());
  await assertFails(aliceRoot.collection("playlists").get());
  await assertFails(aliceRoot.collection("tracks").doc("sp_track_1").set(validTrack(BOB_UID)));
  await assertFails(aliceRoot.collection("playlists").doc("pl_1").delete());
});

test("Rejects shadow ghost fields, spoofed userId, invalid doc IDs, and orphaned playlist items", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const aliceDoc = aliceDb.collection("users").doc(ALICE_UID);

  // Ghost field in track create
  await assertFails(
    aliceDoc.collection("tracks").doc("sp_track_1").set({
      ...validTrack(ALICE_UID),
      isAdmin: true,
    })
  );

  // Spoofed userId
  await assertFails(
    aliceDoc.collection("tracks").doc("sp_track_1").set(validTrack(BOB_UID))
  );

  // Orphaned playlist item (parent playlist does not exist)
  await assertFails(
    aliceDoc.collection("playlists").doc("missing_pl").collection("items").doc("sp_track_1").set(
      validPlaylistItem(ALICE_UID, "missing_pl", "sp_track_1")
    )
  );

  // Immutable trackId cannot be changed on update
  await assertSucceeds(aliceDoc.collection("tracks").doc("sp_track_1").set(validTrack(ALICE_UID)));
  await assertFails(
    aliceDoc.collection("tracks").doc("sp_track_1").update({
      trackId: "tampered_id",
      updatedAt: ts(),
    })
  );
});

test("User profile supports custom bio, compressed avatar data URI, and synced settings map", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const aliceDoc = aliceDb.collection("users").doc(ALICE_UID);

  await assertSucceeds(
    aliceDoc.set({
      ...validProfile(ALICE_UID),
      username: "alice_v33",
      displayName: "SoundLap Pro",
      bio: "Basshead & producer",
      photoUrl: "data:image/jpeg;base64,/9j/4AAQSkZJRg==",
      accountUid: "SL-ABCD-1234",
      searchHistory: ["miyagi", "kurokov"],
      listeningStatsJson: "[{\"dayEpoch\":20000,\"trackId\":\"t1\",\"title\":\"Test\",\"artist\":\"Artist\",\"listenedMs\":120000,\"playCount\":2,\"lastPlayedAtMs\":1700000000000}]",
      settings: {
        appLanguage: "ru",
        darkLiquidGlass: true,
        accentColorArgb: 0xff8b5cf6,
        smoothTransitionEnabled: true,
        smoothTransitionDurationSec: 4,
        hapticsEnabled: true,
        hapticIntensity: 80,
        autoCacheOnPlay: true,
        dataSaverEnabled: false,
        autoUploadEnabled: true,
        batterySaverEnabled: true,
        audioFocusMode: "MIX",
        updatedAtMs: 1700000000000,
      },
    })
  );

  // Rejects oversized searchHistory (>30 items)
  await assertFails(
    aliceDoc.update({
      searchHistory: Array.from({ length: 31 }, (_, i) => `q${i}`),
      updatedAt: ts(),
    })
  );

  // Rejects invalid audioFocusMode
  await assertFails(
    aliceDoc.update({
      settings: {
        appLanguage: "en",
        audioFocusMode: "INVALID_MODE",
      },
      updatedAt: ts(),
    })
  );

  // Rejects unknown field inside settings map
  await assertFails(
    aliceDoc.update({
      settings: {
        appLanguage: "en",
        unknownHackField: true,
      },
      updatedAt: ts(),
    })
  );
});

test("Bug reports enforce authenticated create, verified-developer read/delete, and immutability", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const aliceDb = testEnv.authenticatedContext(ALICE_UID, {
    email: "alice@example.com",
    email_verified: true,
  }).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID, {
    email: "bob@example.com",
    email_verified: true,
  }).firestore();

  const validReport = {
    reportId: "rep_1",
    userId: ALICE_UID,
    reporterName: "Alice",
    reporterAvatarUrl: "https://example.com/a.jpg",
    message: "Button alignment issue",
    appVersion: "3.4.1",
    createdAtMs: 1700000001000,
    createdAt: ts(),
  };

  await assertFails(unauthDb.collection("bug_reports").doc("rep_1").set(validReport));
  await assertFails(unauthDb.collection("bug_reports").get());
  await assertFails(bobDb.collection("bug_reports").doc("rep_1").set(validReport));

  await assertSucceeds(aliceDb.collection("bug_reports").doc("rep_1").set(validReport));

  // Unrelated non-dev user cannot list all reports or delete Alice's report
  await assertFails(bobDb.collection("bug_reports").get());
  await assertFails(bobDb.collection("bug_reports").doc("rep_1").get());
  await assertFails(bobDb.collection("bug_reports").doc("rep_1").delete());

  // Reporter can query their own reports and delete their own report, but cannot update
  await assertSucceeds(aliceDb.collection("bug_reports").where("userId", "==", ALICE_UID).get());
  await assertFails(aliceDb.collection("bug_reports").doc("rep_1").update({ message: "Edited" }));
  await assertFails(unauthDb.collection("bug_reports").doc("rep_1").delete());

  if (DEV_TEST_EMAIL) {
    const devDb = testEnv.authenticatedContext("dev_uid_1", {
      email: DEV_TEST_EMAIL,
      email_verified: true,
    }).firestore();
    await assertSucceeds(devDb.collection("bug_reports").get());
    await assertSucceeds(devDb.collection("bug_reports").doc("rep_1").delete());
  } else {
    await assertSucceeds(aliceDb.collection("bug_reports").doc("rep_1").delete());
  }
});

test("Avatar 18+ moderation block deletes cloud photoUrl and prevents re-enabling or uploading new avatar", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const aliceDoc = aliceDb.collection("users").doc(ALICE_UID);

  await assertSucceeds(
    aliceDoc.set({
      ...validProfile(ALICE_UID),
      photoUrl: "data:image/jpeg;base64,/9j/4AAQSkZJRg==",
    })
  );

  // Setting avatarBlocked=true while keeping photoUrl must fail
  await assertFails(
    aliceDoc.update({
      avatarBlocked: true,
      updatedAt: ts(),
    })
  );

  // Revoking avatar: setting avatarBlocked=true and deleting photoUrl succeeds
  const existingSnap = await aliceDoc.get();
  await assertSucceeds(
    aliceDoc.set({
      userId: ALICE_UID,
      displayName: existingSnap.data().displayName,
      email: existingSnap.data().email,
      avatarBlocked: true,
      createdAt: existingSnap.data().createdAt,
      updatedAt: ts(),
    })
  );

  // Once blocked, user can still update displayName/bio without photoUrl
  await assertSucceeds(
    aliceDoc.update({
      displayName: "Alice Renamed",
      updatedAt: ts(),
    })
  );

  // Once blocked, user cannot clear avatarBlocked or set a new photoUrl
  await assertFails(
    aliceDoc.update({
      avatarBlocked: false,
      updatedAt: ts(),
    })
  );
  await assertFails(
    aliceDoc.update({
      photoUrl: "https://example.com/new.jpg",
      updatedAt: ts(),
    })
  );
});

test("Cloud metrics allow owner writes and verified-developer reads while blocking non-dev list queries", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const aliceDb = testEnv.authenticatedContext(ALICE_UID, {
    email: "alice@example.com",
    email_verified: true,
  }).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID, {
    email: "bob@example.com",
    email_verified: true,
  }).firestore();

  const aliceMetrics = {
    userId: ALICE_UID,
    storageBytes: 12480,
    tracksCount: 8,
    playlistsCount: 2,
    updatedAt: ts(),
  };

  await assertFails(unauthDb.collection("cloud_metrics").doc(ALICE_UID).set(aliceMetrics));
  await assertFails(bobDb.collection("cloud_metrics").doc(ALICE_UID).set({ ...aliceMetrics, userId: BOB_UID }));
  await assertSucceeds(aliceDb.collection("cloud_metrics").doc(ALICE_UID).set(aliceMetrics));
  await assertSucceeds(aliceDb.collection("cloud_metrics").doc(ALICE_UID).update({ storageBytes: 9600, updatedAt: ts() }));

  // Non-dev cannot list all cloud_metrics or read Alice's metrics
  await assertFails(bobDb.collection("cloud_metrics").get());
  await assertFails(bobDb.collection("cloud_metrics").doc(ALICE_UID).get());
  await assertSucceeds(aliceDb.collection("cloud_metrics").doc(ALICE_UID).get());

  if (DEV_TEST_EMAIL) {
    const devDb = testEnv.authenticatedContext("dev_uid_1", {
      email: DEV_TEST_EMAIL,
      email_verified: true,
    }).firestore();
    await assertSucceeds(devDb.collection("cloud_metrics").get());
  }
});

test("Public profiles, @username registry, and exclusive verified developer badge boundaries", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const aliceDb = testEnv.authenticatedContext(ALICE_UID, {
    email: "alice@example.com",
    email_verified: true,
  }).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID, {
    email: "bob@example.com",
    email_verified: true,
  }).firestore();

  // Regular user cannot claim isDeveloper: true
  await assertFails(
    aliceDb.collection("public_profiles").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Fake Dev",
      username: "fake_dev",
      isDeveloper: true,
      isPublic: true,
      updatedAt: ts(),
    })
  );

  // Alice writes non-dev public profile and claims @alice_music
  await assertSucceeds(
    aliceDb.collection("public_profiles").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alice",
      username: "alice_music",
      isDeveloper: false,
      isPublic: true,
      updatedAt: ts(),
    })
  );
  await assertSucceeds(
    aliceDb.collection("usernames").doc("alice_music").set({
      username: "alice_music",
      userId: ALICE_UID,
      displayName: "Alice",
      isDeveloper: false,
      isPublic: true,
      updatedAt: ts(),
    })
  );

  // Bob cannot steal Alice's handle or overwrite her public profile
  await assertFails(
    bobDb.collection("usernames").doc("alice_music").set({
      username: "alice_music",
      userId: BOB_UID,
      displayName: "Bob",
      isDeveloper: false,
      isPublic: true,
      updatedAt: ts(),
    })
  );
  await assertFails(bobDb.collection("public_profiles").doc(ALICE_UID).delete());

  if (DEV_TEST_EMAIL) {
    const unverifiedDevDb = testEnv.authenticatedContext("dev_unverified", {
      email: DEV_TEST_EMAIL,
      email_verified: false,
    }).firestore();
    await assertFails(
      unverifiedDevDb.collection("public_profiles").doc("dev_unverified").set({
        userId: "dev_unverified",
        displayName: "Unverified Dev",
        isDeveloper: true,
        isPublic: true,
        updatedAt: ts(),
      })
    );

    const devDb = testEnv.authenticatedContext("dev_uid_1", {
      email: DEV_TEST_EMAIL,
      email_verified: true,
    }).firestore();
    await assertSucceeds(
      devDb.collection("public_profiles").doc("dev_uid_1").set({
        userId: "dev_uid_1",
        displayName: "TylerW1nston",
        username: "tylerw1nston",
        isDeveloper: true,
        isPublic: true,
        updatedAt: ts(),
      })
    );
    await assertSucceeds(
      devDb.collection("usernames").doc("tylerw1nston").set({
        username: "tylerw1nston",
        userId: "dev_uid_1",
        displayName: "TylerW1nston",
        isDeveloper: true,
        isPublic: true,
        updatedAt: ts(),
      })
    );
    await assertSucceeds(bobDb.collection("usernames").doc("tylerw1nston").get());
  }

  // List queries require isPublic==true (or isDeveloper==true); unauthenticated denied
  await assertFails(unauthDb.collection("public_profiles").where("isPublic", "==", true).get());
  await assertFails(bobDb.collection("public_profiles").get());
  await assertSucceeds(bobDb.collection("public_profiles").where("isPublic", "==", true).get());
  await assertSucceeds(bobDb.collection("public_profiles").where("isDeveloper", "==", true).get());
});

test("Friends and artist subscriptions enforce owner isolation and schema validation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  // Adding self as friend fails
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("friends").doc(ALICE_UID).set({
      userId: ALICE_UID,
      friendUid: ALICE_UID,
      displayName: "Self",
      isDeveloper: false,
      addedAtMs: 1700000000000,
      createdAt: ts(),
      updatedAt: ts(),
    })
  );

  // Adding Bob as friend succeeds
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("friends").doc(BOB_UID).set({
      userId: ALICE_UID,
      friendUid: BOB_UID,
      username: "bob_beats",
      displayName: "Bob",
      isDeveloper: false,
      addedAtMs: 1700000000000,
      createdAt: ts(),
      updatedAt: ts(),
    })
  );

  // Artist subscription create/list/delete
  const subRef = aliceDb.collection("users").doc(ALICE_UID).collection("artist_subscriptions").doc("art_miyagi");
  await assertSucceeds(
    subRef.set({
      userId: ALICE_UID,
      artistKey: "art_miyagi",
      artistName: "Miyagi",
      avatarUrl: "https://example.com/miyagi.jpg",
      subscribedAtMs: 1700000000000,
      createdAt: ts(),
      updatedAt: ts(),
    })
  );
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("artist_subscriptions").get());

  // Cross-user access denied
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("friends").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("artist_subscriptions").get());

  await assertSucceeds(subRef.delete());
});

test("User online presence and active track fields validate schema and allow read-only verified developer inspection", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID, {
    email: "alice@example.com",
    email_verified: true,
  }).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID, {
    email: "bob@example.com",
    email_verified: true,
  }).firestore();
  const aliceDoc = aliceDb.collection("users").doc(ALICE_UID);

  await assertSucceeds(aliceDoc.set(validProfile(ALICE_UID)));
  await assertSucceeds(
    aliceDoc.update({
      isOnline: true,
      lastActiveAtMs: 1700000002000,
      currentTrackId: "sp_track_1",
      currentTrackTitle: "Yamakasi",
      currentTrackArtist: "Miyagi",
      currentTrackCoverUrl: "https://i.scdn.co/image/cover",
      currentTrackIsPlaying: true,
      currentTrackUpdatedAtMs: 1700000002000,
      updatedAt: ts(),
    })
  );

  // Regular user cannot read Alice's presence/private user doc or list all users
  await assertFails(bobDb.collection("users").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());

  if (DEV_TEST_EMAIL) {
    const devDb = testEnv.authenticatedContext("dev_uid_1", {
      email: DEV_TEST_EMAIL,
      email_verified: true,
    }).firestore();
    await assertSucceeds(devDb.collection("users").get());
    await assertSucceeds(devDb.collection("users").doc(ALICE_UID).get());
    await assertSucceeds(devDb.collection("users").doc(ALICE_UID).collection("tracks").get());
    await assertSucceeds(devDb.collection("users").doc(ALICE_UID).collection("playlists").get());
    await assertSucceeds(devDb.collection("users").doc(ALICE_UID).collection("friends").get());
    await assertSucceeds(devDb.collection("users").doc(ALICE_UID).collection("artist_subscriptions").get());
    // Verified developer cannot mutate Alice's profile
    await assertFails(devDb.collection("users").doc(ALICE_UID).update({ displayName: "Hacked", updatedAt: ts() }));
  }
});

test("Banned user is blocked from all cloud reads/writes while non-developer cannot tamper with banned_users", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID, {
    email: "alice@example.com",
    email_verified: true,
  }).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID, {
    email: "bob@example.com",
    email_verified: true,
  }).firestore();

  // Seed Alice's data before ban
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).set(validProfile(ALICE_UID)));
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("tracks").doc("t1").set(validTrack(ALICE_UID, "t1")));

  // Regular user cannot create a ban record or delete another user's profile
  await assertFails(
    bobDb.collection("banned_users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      reason: "Fake ban",
      bannedByUid: BOB_UID,
      bannedAtMs: 1700000003000,
      updatedAt: ts(),
    })
  );
  await assertFails(bobDb.collection("users").doc(ALICE_UID).delete());

  // Seed ban record using disabled rules (or verified dev when DEV_TEST_EMAIL is provided)
  if (DEV_TEST_EMAIL) {
    const devDb = testEnv.authenticatedContext("dev_uid_1", {
      email: DEV_TEST_EMAIL,
      email_verified: true,
    }).firestore();
    await assertSucceeds(
      devDb.collection("banned_users").doc(ALICE_UID).set({
        userId: ALICE_UID,
        reason: "Abuse",
        bannedByUid: "dev_uid_1",
        bannedAtMs: 1700000003000,
        updatedAt: ts(),
      })
    );
    // Developer cannot ban themselves
    await assertFails(
      devDb.collection("banned_users").doc("dev_uid_1").set({
        userId: "dev_uid_1",
        reason: "Self ban",
        bannedByUid: "dev_uid_1",
        bannedAtMs: 1700000003000,
        updatedAt: ts(),
      })
    );
  } else {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await ctx.firestore().collection("banned_users").doc(ALICE_UID).set({
        userId: ALICE_UID,
        reason: "Abuse",
        bannedByUid: "dev_uid_1",
        bannedAtMs: 1700000003000,
        updatedAt: ts(),
      });
    });
  }

  // Alice can read her own ban status doc so app shows Ban Screen
  await assertSucceeds(aliceDb.collection("banned_users").doc(ALICE_UID).get());
  // Alice cannot delete or tamper with her own ban doc
  await assertFails(aliceDb.collection("banned_users").doc(ALICE_UID).delete());
  // Bob cannot read Alice's ban record
  await assertFails(bobDb.collection("banned_users").doc(ALICE_UID).get());

  // Banned Alice is blocked from reading/writing own data or public profiles
  await assertFails(aliceDb.collection("users").doc(ALICE_UID).get());
  await assertFails(aliceDb.collection("users").doc(ALICE_UID).collection("tracks").get());
  await assertFails(aliceDb.collection("users").doc(ALICE_UID).update({ displayName: "Alice2", updatedAt: ts() }));
  await assertFails(aliceDb.collection("public_profiles").where("isPublic", "==", true).get());
  await assertFails(aliceDb.collection("cloud_metrics").doc(ALICE_UID).set({
    userId: ALICE_UID,
    storageBytes: 100,
    tracksCount: 0,
    playlistsCount: 0,
    updatedAt: ts(),
  }));

  if (DEV_TEST_EMAIL) {
    const devDb = testEnv.authenticatedContext("dev_uid_1", {
      email: DEV_TEST_EMAIL,
      email_verified: true,
    }).firestore();
    await assertSucceeds(devDb.collection("banned_users").doc(ALICE_UID).delete());
    await assertSucceeds(devDb.collection("users").doc(ALICE_UID).collection("tracks").doc("t1").delete());
    await assertSucceeds(devDb.collection("users").doc(ALICE_UID).delete());
    // Unbanned Alice is now able to recreate profile
    await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).set(validProfile(ALICE_UID)));
  }
});



