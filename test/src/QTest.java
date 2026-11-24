import com.qcaller.announcer.QueueStore;

public class QTest {
    static int fails = 0;

    static void check(String what, Object got, Object want) {
        boolean ok = String.valueOf(got).equals(String.valueOf(want));
        if (!ok) fails++;
        System.out.println((ok ? "  OK  " : " FAIL ") + what + "  got=" + got + " want=" + want);
    }

    public static void main(String[] a) {
        System.out.println("== 场景1：连续取号 6 次 ==");
        QueueStore q = new QueueStore();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            int n = q.take(1);
            if (sb.length() > 0) sb.append(",");
            sb.append(n);
        }
        check("号码连续递增", sb.toString(), "1,2,3,4,5,6");
        check("等待人数", q.waitCount(), 6);

        System.out.println("== 场景2：按取号顺序叫号 ==");
        StringBuilder called = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            int n = q.callNext();
            if (called.length() > 0) called.append(",");
            called.append(n);
        }
        check("叫号顺序 = 取号顺序", called.toString(), "1,2,3,4,5,6");
        check("队列已空", q.waitCount(), 0);
        check("空队列再叫返回 -1", q.callNext(), -1);

        System.out.println("== 场景3：边取边叫（取3个，叫1个办结，再取1个）==");
        QueueStore r = new QueueStore();
        r.take(1); r.take(1); r.take(1);
        int c1 = r.callNext();
        check("第一次叫号", c1, 1);
        r.complete();
        check("办结后 current 清零", r.current, 0);
        check("剩余等待", r.waitCount(), 2);
        int n4 = r.take(1);
        check("呼叫中继续取号不发重号", n4, 4);
        check("再次叫号按顺序", r.callNext(), 2);
        check("已发号计数", r.issued, 4);

        System.out.println("== 场景4：过号回队首 ==");
        QueueStore s = new QueueStore();
        s.take(1); s.take(1); s.take(1);
        int f = s.callNext();     // 1
        s.backToQueue();
        check("过号回队首后下一个仍是它", s.callNext(), 1);
        check("再下一个", s.callNext(), 2);

        System.out.println("== 场景5：指定号码呼叫 ==");
        QueueStore t = new QueueStore();
        for (int i = 0; i < 4; i++) t.take(1);
        t.callNumber(3);
        check("指定叫 3 号", t.current, 3);
        check("3 号已出队", t.waiting.contains(3), false);
        check("剩余等待", t.waitCount(), 3);
        check("下一个按顺序", t.callNext(), 1);

        System.out.println("== 场景6：起始号 100 ==");
        QueueStore u = new QueueStore();
        check("首号 = 起始号", u.take(100), 100);
        check("第二个", u.take(100), 101);

        System.out.println(fails == 0 ? "\n全部通过 ✓" : "\n失败 " + fails + " 项 ✗");
        System.exit(fails == 0 ? 0 : 1);
    }
}
