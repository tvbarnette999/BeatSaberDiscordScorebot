package beatsaber.scorebot.quest;

import com.mongodb.*;
import com.mongodb.client.*;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.*;
import com.mongodb.operation.AggregateOperation;
import org.bson.Document;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

import static com.mongodb.client.model.Filters.eq;

public class DAO {
    private static final Logger LOG = LoggerFactory.getLogger(DAO.class);
    MongoDatabase database;
    MongoCollection<User> userCollection;
    MongoCollection<Level> levelCollection;
    MongoCollection<Score> scoreCollection;
    MongoCollection<Version> versionCollection;
    private static final String[] collectionList = {"users", "songs", "scores", "versions"};
    private static final int DB_VERSION = -1;

    protected static String getConnectionString() {
        String un = Config.getProperty("db.username");
        String pw = Config.getProperty("db.password");
        String url = Config.getProperty("db.url");
        return url.contains("localhost") ? "mongodb://localhost/?retryWrites=true&w=majority" : "mongodb+srv://" + un + ":" + pw + "@" + url + "/?retryWrites=true&w=majority";
    }

    public DAO() {
        CodecRegistry pojoCodecRegistry = org.bson.codecs.configuration.CodecRegistries.fromRegistries(MongoClientSettings.getDefaultCodecRegistry(), org.bson.codecs.configuration.CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build()));
        String db = Config.getProperty("db.db");
        MongoClient mongoClient = MongoClients.create(getConnectionString());
        database = mongoClient.getDatabase(db).withCodecRegistry(pojoCodecRegistry);
        // verify collection existence
        List<String> cols = database.listCollectionNames().into(new ArrayList<>());
        Arrays.stream(collectionList).filter(x -> !cols.contains(x)).forEach(database::createCollection);
        // init collection references
        userCollection = database.getCollection("users", User.class);
        levelCollection = database.getCollection("levels", Level.class);
        scoreCollection = database.getCollection("scores", Score.class);
        versionCollection = database.getCollection("versions", Version.class);
        // verify versioning
        runMigrations();
    }

    private final Runnable[] MIGRATIONS = new Runnable[] {
        /* v0 */ () -> {}// initial run & config
    };

    /**
     * Used if anything changes with regard to db structure or if all existing fields need to be updated
     */
    private void runMigrations() {
        Map<Integer, Version> applied = new HashMap<>();
        versionCollection.find(new Document()).sort(Sorts.ascending("version")).forEach((Block<? super Version>) x -> applied.put(x.getVersion(), x));
        List<Version> toMigrate = new ArrayList<>();
        for (int i = 0; i < MIGRATIONS.length; i++) {
            if (!applied.containsKey(i)) {
                Version v = new Version();
                v.setVersion(i);
                v.setUpdateTime(System.currentTimeMillis());
                v.setId(new ObjectId());
                try {
                    versionCollection.insertOne(v);
                    toMigrate.add(v); // this instance claims responsibility for adding this migration if the insert succeeds
                } catch (DuplicateKeyException e) {
                    // someone else claimed it
                }
            }
        }
        for (Version v : toMigrate) {
            // verify this instance has responsibility for performing migration, then perform it
            Version inDb = versionCollection.find(eq("version", v.getVersion())).first();
            if (inDb == null || (inDb.getId().equals(v.getId()) && inDb.getUpdateTime() == v.getUpdateTime())) {
                MIGRATIONS[v.getVersion()].run();
            }
        }
    }

    private Bson byId(ObjectId id) {
        return eq("_id", id);
    }

    public User getUserByUserId(long userId) {
        return userCollection.find(Filters.eq("userId", userId)).first();
    }

    public void saveUser(User user) {
        ReplaceOptions opt = new ReplaceOptions();
        opt.upsert(true);
        userCollection.replaceOne(byId(user.getId()), user, opt);
    }

    public User getUserByDiscordId(long id) {
        return userCollection.find(Filters.eq("discordId", id)).first();
    }

    public Level getLevelByHash(String hash) {
        return levelCollection.find(Filters.eq("hash", hash)).first();
    }

    public void saveLevel(Level level) {
        levelCollection.insertOne(level);
    }

    public Score getScore(String songHash, int difficulty, long userDiscordId) {
        return scoreCollection.find(Filters.and(Filters.eq("songHash", songHash), Filters.eq("difficulty", difficulty), Filters.eq("userDiscordId", userDiscordId))).first();
    }

    public void saveScore(Score score) {
        ReplaceOptions opt = new ReplaceOptions();
        opt.upsert(true);
        scoreCollection.replaceOne( Filters.and(Filters.eq("songHash", score.songHash), Filters.eq("difficulty", score.difficulty), Filters.eq("userDiscordId", score.userDiscordId)), score, opt);
    }

    public List<Score> getLeaderboard(String songHash, int difficulty) {
        return scoreCollection.find(Filters.and(Filters.eq("songHash", songHash), Filters.eq("difficulty", difficulty)))
                .sort(Sorts.orderBy(Sorts.descending("score"), Sorts.ascending("timestamp")))
                .into(new ArrayList<>());
    }

    public List<Level> getCommonSongs(int limit, long ... discordIds) {
        List<Long> longArray = Arrays.stream(discordIds).boxed().collect(Collectors.toList());
        AggregateIterable<Level> agg = scoreCollection.aggregate(Arrays.asList(
                Aggregates.group("$songHash", Accumulators.addToSet("users", "$userDiscordId")),
                Aggregates.match(Filters.all("users", longArray)),
                Aggregates.sample(limit),
                Aggregates.lookup("levels", "_id", "hash", "levels"),
                Aggregates.replaceRoot(new Document("$arrayElemAt", Arrays.asList("$levels", 0)))
            ), Level.class);
        return agg.into(new ArrayList<>());
    }

    private Bson cond(String field, Object v) {
        return Filters.eq("$cond", Filters.and(Filters.eq("if", new Document("$eq", Arrays.asList("$rank", v))), Filters.eq("then", 1L), Filters.eq("else", 0L)));
    }

    /** Return the number of 1st, 2nd, and 3rd places scores each user has*/
    public List<Leaderboard> getLeaderboard() {
        return scoreCollection.aggregate(Arrays.asList(
                Aggregates.sort(Sorts.orderBy(Sorts.descending("score"), Sorts.ascending("timestamp"))),
                Aggregates.group(
                        Filters.and(Filters.eq("hash", "$songHash"), Filters.eq("diff", "$difficulty")),
                        Accumulators.push("scores", Filters.and(Filters.eq("discordId", "$userDiscordId"), Filters.eq("score", "$score")))),
                Aggregates.unwind("$scores", new UnwindOptions().includeArrayIndex("rank").preserveNullAndEmptyArrays(false)),
                Aggregates.group("$scores.discordId",
                        Accumulators.sum("gold", cond("$rank", 0L)),
                        Accumulators.sum("silver", cond("$rank", 1L)),
                        Accumulators.sum("bronze", cond("$rank", 2L))),
                Aggregates.lookup("users", "_id", "discordId", "users"),
                Aggregates.project(Projections.fields(Projections.computed("user", new Document("$arrayElemAt", Arrays.asList("$users", 0))), Projections.include("gold", "silver", "bronze"))),
                Aggregates.sort(Sorts.descending("gold", "silver", "bronze"))
        ), Leaderboard.class).into(new ArrayList<>());
    }

    public List<Level> getLevelsByName(String name) {
        // Text query/index?
        return levelCollection.find(Filters.regex("songName", ".*" + name + ".*", "i")).into(new ArrayList<>());
    }

    public Level getLevelByNameExact(String name) {
        return levelCollection.find(Filters.eq("name", name)).first();
    }

    public Level getLevelById(String id) {
        return levelCollection.find(Filters.eq("_id", id)).first();
    }
    public int getHighestDifficulty(String hash) {
        Score s = scoreCollection.find(eq("songHash", hash)).sort(Sorts.descending("difficulty")).projection(Projections.include("difficulty")).limit(1).first();
        return s == null ? -1 : s.difficulty;
    }
    public List<Score> getAllScores(long postedSince) {
        return scoreCollection.find(Filters.gte("timestamp", postedSince)).projection(Projections.include("songHash", "difficulty", "userDiscordId", "score", "fullCombo")).into(new ArrayList<>());
    }
    public List<Level> getAllLevels(long since) {
        return levelCollection.find(Filters.gte("timestamp", since)).into(new ArrayList<>());
    }
}
