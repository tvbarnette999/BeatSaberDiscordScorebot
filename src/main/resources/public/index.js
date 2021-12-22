let users = [];
let levels = [];
let scores = [];
function initLeaderboard() {
    let A = fetch("/users").then(r => r.json()).then(d => {
        users = d;
        let msg = users.filter(x => x.scoresaberIdString).map(x => x.scoresaberIdString);
        scoresaberWorker.postMessage(msg);
    });
    let B = fetch("/levels").then(r => r.json()).then(d => {
        levels = d;
    });
    let C = fetch("/scores").then(r => r.json()).then(d => {
        scores = d;
    });
    Promise.allSettled([A, B, C]).then(combineData);
}

function combineData() {
    const map = new Map();
    for (let score of scores) {
        let k = score.songHash + "#" + score.difficulty;
        if (!map.has(k)) {
            let level = levels.find(x => x.hash === score.songHash);
            if (!level) continue;
            map.set(k, {level: level, hash:  score.songHash, difficulty: score.difficulty, scores: {}, scoreCount: 0});
        }
        let v = map.get(k);
        v.scores[score.userDiscordId] = {score: score.score, fc: score.fullCombo, accuracy: score.accuracy, grade: score.accuracyRank};
        v.scoreCount++;
    }
    data = Array.from(map.values());
    for (let row of data) {
        for (let k1 in row.scores) {
            row.scores[k1].localRank = 1;
            for (let k2 in row.scores) {
                if (k1 !== k2 && row.scores[k2].score > row.scores[k1].score) {
                    row.scores[k1].localRank++;
                }
            }
        }
    }
    render();
}