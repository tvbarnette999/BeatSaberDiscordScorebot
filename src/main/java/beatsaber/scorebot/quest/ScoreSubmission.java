package beatsaber.scorebot.quest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ScoreSubmission {
    String userId;
    String levelId;
    String songName;
    String songAuthor;
    String levelAuthor;
    String difficulty;
    int difficultyRank;
    int difficultyRaw;
    int score;
    int modifiedScore;
    boolean fullCombo;
    double leftSaberDistance;
    double leftHandDistance;
    double rightSaberDistance;
    double rightHandDistance;
    int goodCuts;
    int badCuts;
    int missed;
    int notGood;
    int ok;
    int averageCutScore;
    int maxCutScore;
    double averageCutDistanceRawScore;
    int maxCombo;
    double minDirDeviation;
    double maxDirDeviation;
    double averageDirDeviation;
    double minTimeDeviation;
    double maxTimeDeviation;
    double averageTimeDeviation;
}