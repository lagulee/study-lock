using StudyLock;
using Xunit;

public class RulesTests
{
    [Theory]
    [InlineData(@"C:\Games\Game.EXE", "game")]
    [InlineData(" Discord ", "discord")]
    [InlineData("explorer.exe", null)]
    [InlineData("StudyLock", null)]
    [InlineData("", null)]
    public void 프로그램_이름을_정리한다(string raw, string? expected) => Assert.Equal(expected, Rules.NormalizeProgram(raw));

    [Fact]
    public void 창_제목에서_막을_낱말을_찾는다()
    {
        string[] words = ["YouTube", " 나무위키 ", ""];
        Assert.Equal("YouTube", Rules.MatchKeyword("(3) 재밌는 영상 - youtube - Chrome", words));
        Assert.Equal("나무위키", Rules.MatchKeyword("이차방정식 - 나무위키 - Whale", words));
        Assert.Null(Rules.MatchKeyword("수학 공부 - Google 검색", words));
        Assert.Null(Rules.MatchKeyword("", words));
    }

    [Fact]
    public void 남은_시간_글자() => Assert.Equal("12분 30초", Rules.Remain(750_000));
}

public class SyncProtocolTests
{
    [Fact]
    public void 주소와_코드를_정리한다()
    {
        Assert.Equal("https://a.workers.dev", SyncProtocol.NormalizeServer(" a.workers.dev/ "));
        Assert.Equal("ABCDEFGHJKMN", SyncProtocol.NormalizeCode("abcd-efgh jkmn"));
        Assert.Null(SyncProtocol.NormalizeCode("ABCD-EFGH-IJK0"));
        Assert.Equal("ABCD-EFGH-JKMN", SyncProtocol.Pretty("ABCDEFGHJKMN"));
        Assert.Equal("wss://a.workers.dev/g/ABCDEFGHJKMN/ws?name=%EC%BB%B4%ED%93%A8%ED%84%B0",
            SyncProtocol.WsUrl("a.workers.dev", "ABCDEFGHJKMN", "컴퓨터"));
    }

    [Fact]
    public void 서버_시각을_이_기기_시계로_옮긴다()
    {
        var json = """{"type":"state","now":10000,"devices":["폰","탭"],"state":{"rev":3,"active":true,"task":"수학","startedAt":4000,"waitMinutes":10,"waitUntil":9000,"by":"폰","ended":null}}""";
        var r = SyncProtocol.Parse(json, localNow: 7000)!;
        Assert.True(r.Active);
        Assert.Equal(1000, r.StartedAt);
        Assert.Equal(6000, r.WaitUntil);
        Assert.Equal(new[] { "폰", "탭" }, r.Devices);
    }

    [Fact]
    public void 동작은_빈_칸_없이_보낸다()
    {
        var json = SyncProtocol.OpJson(new SyncOp { Type = "wait", By = "pc" });
        Assert.Contains("\"type\": \"wait\"", json);
        Assert.Contains("\"by\": \"pc\"", json);
        Assert.DoesNotContain("task", json);
    }

    [Fact]
    public void 오류_응답을_알기_쉽게()
    {
        Assert.Null(SyncProtocol.ErrorOf(200, "{}"));
        Assert.True(SyncProtocol.ErrorOf(404, """{"error":"noGroup"}""")!.Permanent);
        Assert.False(SyncProtocol.ErrorOf(503, "")!.Permanent);
    }
}

public class StoreTests
{
    private static string NewPath() => Path.Combine(Path.GetTempPath(), "studylock-test-" + Guid.NewGuid(), "data.json");

    [Fact]
    public void 연동하면_동작이_쌓이고_대기가_끝나면_풀린다()
    {
        var path = NewPath();
        var s = new Store(path);
        s.D.SyncServer = "a.workers.dev";
        s.D.SyncCode = "ABCDEFGHJKMN";
        s.D.Task = "영어 단어";
        s.D.WaitMinutes = 10;
        s.StartSession(1_000);
        s.StartWait(2_000);
        Assert.Equal(602_000, s.D.WaitUntil);
        Assert.False(s.FinishWaitIfDone(601_999));
        Assert.True(s.FinishWaitIfDone(602_000));
        Assert.Equal(new[] { "start", "wait", "end" }, s.D.PendingOps.Select(o => o.Type));
        Assert.Equal("대기 해제", s.D.Records[0].Method);

        // 파일에서 다시 읽어도 같다
        var again = new Store(path);
        Assert.Equal(3, again.D.PendingOps.Count);
    }

    [Fact]
    public void 다른_기기의_시작과_해제를_따라간다()
    {
        var s = new Store(NewPath());
        s.ApplyRemote(new Remote(1, true, "수학", 500, 20, 0, "폰", "", "", "", ["폰"]), 600);
        Assert.True(s.D.Active);
        Assert.Equal("폰", s.D.SessionBy);
        s.ApplyRemote(new Remote(2, false, "수학", 500, 20, 0, "폰", "Gemini 인증", "폰", "잘했어요", ["폰"]), 9_000);
        Assert.False(s.D.Active);
        Assert.Equal("Gemini 인증 (폰)", s.D.Records[0].Method);
        Assert.Empty(s.D.PendingOps); // 연동하지 않으면 쌓지 않음
    }
}
