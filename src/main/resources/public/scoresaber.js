let db;
let scoresaberUsers;
let scoresaberScores = {};
let lastScore = {};
let eventTarget = null;
let scoresaber = true;
let dataMap = {};
let dataArray = [];
function initScoresaberCache() {
    if (!window.indexedDB) {
        console.warn("Your browser doesn't support a stable version of IndexedDB. Scoresaber data will not be fetched");
        scoresaber = false;
        return;
    }
    let request = window.indexedDB.open("ScoresaberCache", 3);
    request.onerror = (event) => {
        // Do something with request.errorCode!
    };
    request.onupgradeneeded = (event) => {
        console.log(event.oldVersion + " -> " + event.newVersion);
        // Save the IDBDatabase interface
        var db = event.target.result;
        // Create an objectStore for this database
        let store = null;
        if (event.oldVersion < 1) {
            store = db.createObjectStore("scores", {keyPath: ["user", "songHash", "difficulty"]});
        } else {
            store = request.transaction.objectStore("scores");
        }
        switch(event.oldVersion) {
            case 0: //handled special earlier
            case 1: //empty
            case 2:
            case 3:
                store.createIndex("ts", "timeSet");
                store.createIndex("user", "user");
                store.createIndex("song", ["songHash", "difficulty"]);
        }
    };
    request.onsuccess = (event) => {
        db = event.target.result;
        // prep event listener
        eventTarget = $("#event");
        eventTarget[0].addEventListener('userLoaded', handleDoneLoadingUser, false);
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
    fetch(`https://new.scoresaber.com/api/player/${userId}/full`).then(r => r.json()).then(d => {
        userData[userId] = d;
        updateUser(userId)
    });
}
function updateUser(userId, page = 1, toSave, retry = 0) {
    if (!toSave) {
        toSave = [];
    }
    fetch(`https://new.scoresaber.com/api/player/${userId}/scores/recent/${page}`)
        .then( r => {
            let done = function() {
                let store = db.transaction("scores", 'readwrite').objectStore("scores");
                toSave.forEach((s) => {
                    let r = store.add(s);
                    r.onerror = (e) => console.log("error", s, e);
                })
                const ev = new CustomEvent('userLoaded', { detail: userId });
                eventTarget[0].dispatchEvent(ev);
            }
            if (!r.ok) {
                done()
                return; // done
            }
            r.json().then(d => {
                for (let i = 0; i < d.scores.length; i++) {
                    d.scores[i].user = userId;
                    if (new Date(d.scores[i].timeSet) <= lastScore[userId]) {
                        console.log("Received score already cached - halting fetch for user " + userId);
                        done();
                        return;
                    }
                    toSave.push(d.scores[i]);
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
                let k = cursor.value.songHash + ':' + cursor.value.difficulty;
                if (!dataMap[k]) {
                    dataMap[k] = {
                        leaderboardId: cursor.value.leaderboardId,
                        songHash: cursor.value.songHash,
                        songName: cursor.value.songName,
                        songSubName: cursor.value.songSubName,
                        songAuthorName: cursor.value.songAuthorName,
                        levelAuthorName: cursor.value.levelAuthorName,
                        difficulty: cursor.value.difficulty,
                        difficultyRaw: cursor.value.difficultyRaw,
                        users: 0,
                        scores: {}
                    };
                }
                dataMap[k].users++;
                dataMap[k].scores[cursor.value.user] = {
                    score: cursor.value.score,
                    scoreId: cursor.value.scoreId,
                    rank: cursor.value.rank,
                    unmodifiedScore: cursor.value.unmodififiedScore,
                    mods: cursor.value.mods,
                    pp: cursor.value.pp,
                    weight: cursor.value.weight,
                    timeSet: cursor.value.timeSet,
                    maxScore: cursor.value.maxScore
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
            mergeScoresaberData(dataArray);
        }
    }
}