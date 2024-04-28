let db;
let scoresaberUsers;
let scoresaberScores = {};
let lastScore = {};
let eventTarget = null;
let scoresaber = true;
let dataMap = {};
let dataArray = [];
onmessage = function (e) {
    console.log("Worker got users", e.data);
    scoresaberUsers = e.data;
    initScoresaberCache();
}
function initScoresaberCache() {
    if (!indexedDB) {
        console.warn("Your browser doesn't support a stable version of IndexedDB. Scoresaber data will not be fetched");
        scoresaber = false;
        return;
    }
    let request = indexedDB.open("ScoresaberCache", 5);
    console.log(request);
    request.onerror = (event) => {
        // Do something with request.errorCode!
        console.log("DB Error!");
    };
    request.onblocked = (event) => {
        console.log("blocked!");
    }
    request.onupgradeneeded = (event) => {
        console.log(event.oldVersion + " -> " + event.newVersion);
        // Save the IDBDatabase interface
        let db = event.target.result;
        // Create an objectStore for this database
        let store = null;
        if (event.oldVersion < 5) {
            db.deleteObjectStore("scores");
            store = db.createObjectStore("scores", {keyPath: ["user", "leaderboard.songHash", "leaderboard.difficulty.difficulty"]});
        } else {
            store = request.transaction.objectStore("scores");
        }
        switch(event.oldVersion) {
            // prior versions had to be wiped.
            case 5:
                break;
        }
    };
    request.onsuccess = (event) => {
        db = event.target.result;
        console.log("DB Opened!");
        loadScoresaberData();
    }
}

function loadScoresaberData() {
    let tx = db.transaction("scores").objectStore("scores");
    tx.openCursor().onsuccess = (event) => {
        var cursor = event.target.result;
        if (cursor) {
            // record the most recent score for each user
            if (cursor.value.timeSet) {
                let date = new Date(cursor.value.timeSet);
                if (!lastScore[cursor.value.user] || lastScore[cursor.value.user].getTime() < date) {
                    lastScore[cursor.value.user] = date;
                }
            }
            // create the merged score objects for the data table
            cursor.continue();
        }
        else {
            console.log("Loaded all entries from DB. Fetching data from scoresaber.");
            updateData();
        }
    }
}

function updateData() {
    for (let i = 0; i < scoresaberUsers.length; i++) {
        getUserData(scoresaberUsers[i]);
    }
}
let userData = {};
function getUserData(userId) {
    // https://scoresaber.com/api/player/${userId}/full
    fetch(`proxy/full/${userId}`).then(r => r.json()).then(d => {
        userData[userId] = d;
        updateUser(userId)
    });
}
const DAY = 60000 * 60 * 24;
function updateUser(userId, page = 1, toSave, retry = 0) {
    if (!toSave) {
        toSave = [];
    }
    //`https://scoresaber.com/api/player/${userId}/scores/recent/${page}`
    fetch(`proxy/scores/${userId}/${page}`)
        .then( r => {
            let done = function() {
                let store = db.transaction("scores", 'readwrite').objectStore("scores");
                toSave.forEach((s) => {
                    let r = store.put(s);
                    r.onerror = (e) => console.log("error", s, e);
                })
                const ev = new CustomEvent('userLoaded', { detail: userId });
                handleDoneLoadingUser(ev);
            }
            if (!r.ok) {
                done()
                return; // done
            }
            r.json().then(d => {
                if (!d.playerScores || d.playerScores.length === 0) {
                    done();
                    return;
                }
                for (let i = 0; i < d.playerScores.length; i++) {
                    d.playerScores[i].user = userId;
                    d.playerScores[i].cacheTime = Date.now();
                    if (new Date(d.playerScores[i].score.timeSet) <= lastScore[userId] && d.playerScores[i].cacheTime > Date.now() - DAY) {
                        console.log("Received score already cached - halting fetch for user " + userId);
                        done();
                        return;
                    }
                    toSave.push(d.playerScores[i]);
                }
                updateUser(userId, page + 1, toSave);
            })
        }).catch((err) => {
            retry++;
            if (retry > 5) {
                console.error("Failed to update data");
                return;
            }
            console.error("Failed to fetch, retry " + (retry));
            updateUser(userId, page, toSave, retry);
    });
}
let loaded = {};
function handleDoneLoadingUser(e) {
    loaded[e.detail] = true;
    for (let i = 0; i < scoresaberUsers.length; i++) {
        if(!loaded[scoresaberUsers[i]]) {
            return;
        }
    }
    // if we get this far, everyone is loaded!
    console.log("All users loaded! Creating data model.")
    loadCompleteDataset();
}

function loadCompleteDataset() {
    let tx = db.transaction("scores").objectStore("scores");
    tx.openCursor().onsuccess = (event) => {
        var cursor = event.target.result;
        if (cursor) {
            if (scoresaberUsers.indexOf(cursor.value.user) >= 0) { // ignore entries for users removed
                // create the merged score objects for the data table
                let k = cursor.value.leaderboard.songHash + ':' + cursor.value.leaderboard.difficulty.difficulty;
                if (!dataMap[k]) {
                    dataMap[k] = {
                        leaderboardId: cursor.value.leaderboard.leaderboardId,
                        songHash: cursor.value.leaderboard.songHash,
                        songName: cursor.value.leaderboard.songName,
                        songSubName: cursor.value.leaderboard.songSubName,
                        songAuthorName: cursor.value.leaderboard.songAuthorName,
                        levelAuthorName: cursor.value.leaderboard.levelAuthorName,
                        difficulty: cursor.value.leaderboard.difficulty.difficulty,
                        difficultyRaw: cursor.value.leaderboard.difficulty.difficultyRaw,
                        users: 0,
                        scores: {}
                    };
                }
                dataMap[k].users++;
                dataMap[k].scores[cursor.value.user] = {
                    score: cursor.value.score.modifiedScore,
                    scoreId: cursor.value.score.id,
                    rank: cursor.value.score.rank,
                    unmodifiedScore: cursor.value.score.baseScore,
                    mods: cursor.value.score.modifiers,
                    pp: cursor.value.score.pp,
                    weight: cursor.value.score.weight,
                    timeSet: cursor.value.score.timeSet,
                    maxScore: cursor.value.leaderboard.maxScore
                }
            }
            cursor.continue();
        }
        else {
            for (let k in dataMap) {
                for (k1 in dataMap[k].scores) {
                    dataMap[k].scores[k1].localRank = 1;
                    for (k2 in dataMap[k].scores) {
                        if (k1 !== k2 && dataMap[k].scores[k2].score > dataMap[k].scores[k1].score) {
                            dataMap[k].scores[k1].localRank++;
                        }
                    }
                }
                dataArray.push(dataMap[k]);
            }
            console.log("Full scoresaber dataset loaded.");
            postMessage(dataArray);
        }
    }
}