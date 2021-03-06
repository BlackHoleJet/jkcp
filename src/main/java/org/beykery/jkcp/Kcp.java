/**
 *  KCP - A Better ARQ Protocol Implementation
 *  skywind3000 (at) gmail.com, 2010-2011
 *  Features:
 *  + Average RTT reduce 30% - 40% vs traditional ARQ like tcp.
 *  + Maximum RTT reduce three times vs tcp.
 *  + Lightweight, distributed as a single source file.
 */
package org.beykery.jkcp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import java.util.LinkedList;

/**
 *
 * @author beykery
 */
public class Kcp
{

  public static final int IKCP_RTO_NDL = 30;  // no delay min rto
  public static final int IKCP_RTO_MIN = 100; // normal min rto
  public static final int IKCP_RTO_DEF = 200;
  public static final int IKCP_RTO_MAX = 60000;
  public static final int IKCP_CMD_PUSH = 81; // cmd: push data
  public static final int IKCP_CMD_ACK = 82; // cmd: ack
  public static final int IKCP_CMD_WASK = 83; // cmd: window probe (ask)
  public static final int IKCP_CMD_WINS = 84; // cmd: window size (tell)
  public static final int IKCP_ASK_SEND = 1;  // need to send IKCP_CMD_WASK
  public static final int IKCP_ASK_TELL = 2;  // need to send IKCP_CMD_WINS
  public static final int IKCP_WND_SND = 32;
  public static final int IKCP_WND_RCV = 32;
  public static final int IKCP_MTU_DEF = 1400;
  public static final int IKCP_ACK_FAST = 3;
  public static final int IKCP_INTERVAL = 100;
  public static final int IKCP_OVERHEAD = 24;
  public static final int IKCP_DEADLINK = 10;
  public static final int IKCP_THRESH_INIT = 2;
  public static final int IKCP_THRESH_MIN = 2;
  public static final int IKCP_PROBE_INIT = 7000;   // 7 secs to probe window size
  public static final int IKCP_PROBE_LIMIT = 120000; // up to 120 secs to probe window

  private final int conv;
  private int mtu;
  private int mss;
  private int state;
  private int snd_una;
  private int snd_nxt;
  private int rcv_nxt;
  private int ts_recent;
  private int ts_lastack;
  private int ssthresh;
  private int rx_rttval;
  private int rx_srtt;
  private int rx_rto;
  private int rx_minrto;
  private int snd_wnd;
  private int rcv_wnd;
  private int rmt_wnd;
  private int cwnd;
  private int probe;
  private int current;
  private int interval;
  private int ts_flush;
  private int xmit;
  private int nodelay;
  private int updated;
  private int ts_probe;
  private int probe_wait;
  private final int dead_link;
  private int incr;
  private final LinkedList<Segment> snd_queue = new LinkedList<>();
  private final LinkedList<Segment> rcv_queue = new LinkedList<>();
  private final LinkedList<Segment> snd_buf = new LinkedList<>();
  private final LinkedList<Segment> rcv_buf = new LinkedList<>();
  private final LinkedList<Integer> acklist = new LinkedList<>();
  private ByteBuf buffer;
  private int fastresend;
  private int nocwnd;
  private int logmask;
  private final Output output;
  private final Object user;
  private int nextUpdate;//the next update time.

  private static int _ibound_(int lower, int middle, int upper)
  {
    return Math.min(Math.max(lower, middle), upper);
  }

  private static int _itimediff(int later, int earlier)
  {
    return later - earlier;
  }

  /**
   * SEGMENT
   */
  class Segment
  {

    private int conv = 0;
    private byte cmd = 0;
    private int frg = 0;
    private int wnd = 0;
    private int ts = 0;
    private int sn = 0;
    private int una = 0;
    private int resendts = 0;
    private int rto = 0;
    private int fastack = 0;
    private int xmit = 0;
    private final ByteBuf data;

    private Segment(int size)
    {
      this.data = PooledByteBufAllocator.DEFAULT.buffer(size);
    }

    /**
     * encode a segment into buffer
     *
     * @param buf
     * @param offset
     * @return
     */
    private int encode(ByteBuf buf)
    {
      int off = buf.writerIndex();
      buf.writeInt(conv);
      buf.writeByte(cmd);
      buf.writeByte(frg);
      buf.writeShort(wnd);
      buf.writeInt(ts);
      buf.writeInt(sn);
      buf.writeInt(una);
      buf.writeInt(data.readableBytes());
      return buf.writerIndex() - off;
    }
  }

  /**
   * create a new kcpcb
   *
   * @param conv
   * @param output
   * @param user
   */
  public Kcp(int conv, Output output, Object user)
  {
    this.conv = conv;
    snd_wnd = IKCP_WND_SND;
    rcv_wnd = IKCP_WND_RCV;
    rmt_wnd = IKCP_WND_RCV;
    mtu = IKCP_MTU_DEF;
    mss = mtu - IKCP_OVERHEAD;
    rx_rto = IKCP_RTO_DEF;
    rx_minrto = IKCP_RTO_MIN;
    interval = IKCP_INTERVAL;
    ts_flush = IKCP_INTERVAL;
    ssthresh = IKCP_THRESH_INIT;
    dead_link = IKCP_DEADLINK;
    buffer = PooledByteBufAllocator.DEFAULT.buffer((mtu + IKCP_OVERHEAD) * 3);
    this.output = output;
    this.user = user;
  }

  /**
   * check the size of next message in the recv queue
   *
   * @return
   */
  public int peekSize()
  {
    if (rcv_queue.isEmpty())
    {
      return -1;
    }
    Segment seq = rcv_queue.getFirst();
    if (0 == seq.frg)
    {
      return seq.data.readableBytes();
    }
    if (rcv_queue.size() < seq.frg + 1)
    {
      return -1;
    }
    int length = 0;
    for (Segment item : rcv_queue)
    {
      length += item.data.readableBytes();
      if (0 == item.frg)
      {
        break;
      }
    }
    return length;
  }

  /**
   * user/upper level recv: returns size, returns below zero for EAGAIN
   *
   * @param buffer
   * @return
   */
  public int receive(ByteBuf buffer)
  {
    if (rcv_queue.isEmpty())
    {
      return -1;
    }
    int peekSize = peekSize();
    if (0 > peekSize)
    {
      return -2;
    }
    if (peekSize > buffer.writableBytes())
    {
      return -3;
    }
    boolean fast_recover = false;
    if (rcv_queue.size() >= rcv_wnd)
    {
      fast_recover = true;
    }
    // merge fragment.
    int count = 0;
    int n = 0;
    for (Segment seg : rcv_queue)
    {
      n += seg.data.readableBytes();
      buffer.writeBytes(seg.data);
      count++;
      if (0 == seg.frg)
      {
        break;
      }
    }
    if (0 < count)
    {
      for (int i = 0; i < count; i++)
      {
        rcv_queue.removeFirst();
      }
    }
    // move available data from rcv_buf -> rcv_queue
    count = 0;
    for (Segment seg : rcv_buf)
    {
      if (seg.sn == rcv_nxt && rcv_queue.size() < rcv_wnd)
      {
        rcv_queue.add(seg);
        rcv_nxt++;
        count++;
      } else
      {
        break;
      }
    }
    if (0 < count)
    {
      for (int i = 0; i < count; i++)
      {
        rcv_buf.removeFirst();
      }
    }
    // fast recover
    if (rcv_queue.size() < rcv_wnd && fast_recover)
    {
      // ready to send back IKCP_CMD_WINS in ikcp_flush
      // tell remote my window size
      probe |= IKCP_ASK_TELL;
    }
    return n;
  }

  /**
   * user/upper level send, returns below zero for error
   *
   * @param buffer
   * @return
   */
  public int send(ByteBuf buffer)
  {
    if (0 == buffer.readableBytes())
    {
      return -1;
    }
    int count;
    if (buffer.readableBytes() < mss)
    {
      count = 1;
    } else
    {
      count = (buffer.readableBytes() + mss - 1) / mss;
    }
    if (255 < count)
    {
      return -2;
    }
    if (0 == count)
    {
      count = 1;
    }
    for (int i = 0; i < count; i++)
    {
      int size;
      if (buffer.readableBytes() > mss)
      {
        size = mss;
      } else
      {
        size = buffer.readableBytes();
      }
      Segment seg = new Segment(size);
      seg.data.writeBytes(buffer, size);
      seg.frg = count - i - 1;
      snd_queue.add(seg);
    }
    return 0;
  }

  /**
   * update ack.
   *
   * @param rtt
   */
  private void update_ack(int rtt)
  {
    if (0 == rx_srtt)
    {
      rx_srtt = rtt;
      rx_rttval = rtt / 2;
    } else
    {
      int delta = rtt - rx_srtt;
      if (0 > delta)
      {
        delta = -delta;
      }
      rx_rttval = (3 * rx_rttval + delta) / 4;
      rx_srtt = (7 * rx_srtt + rtt) / 8;
      if (rx_srtt < 1)
      {
        rx_srtt = 1;
      }
    }
    int rto = rx_srtt + Math.max(1, 4 * rx_rttval);
    rx_rto = _ibound_(rx_minrto, rto, IKCP_RTO_MAX);
  }

  private void shrink_buf()
  {
    if (snd_buf.size() > 0)
    {
      snd_una = snd_buf.getFirst().sn;
    } else
    {
      snd_una = snd_nxt;
    }
  }

  private void parse_ack(int sn)
  {
    if (_itimediff(sn, snd_una) < 0 || _itimediff(sn, snd_nxt) >= 0)
    {
      return;
    }
    int index = 0;
    for (int i = 0; i < snd_buf.size(); i++)
    {
      Segment seg = snd_buf.get(i);
      if (sn == seg.sn)
      {
        snd_buf.remove(index);
        break;
      } else
      {
        seg.fastack++;
      }
      index++;
    }
  }

  private void parse_una(int una)
  {
    int count = 0;
    for (Segment seg : snd_buf)
    {
      if (_itimediff(una, seg.sn) > 0)
      {
        count++;
      } else
      {
        break;
      }
    }
    if (0 < count)
    {
      for (int i = 0; i < count; i++)
      {
        snd_buf.removeFirst();
      }
    }
  }

  private void ack_push(int sn, int ts)
  {
    acklist.add(sn);
    acklist.add(ts);
  }

  private void parse_data(Segment newseg)
  {
    int sn = newseg.sn;
    if (_itimediff(sn, rcv_nxt + rcv_wnd) >= 0 || _itimediff(sn, rcv_nxt) < 0)
    {
      return;
    }
    int n = rcv_buf.size() - 1;
    int after_idx = -1;
    boolean repeat = false;
    for (int i = n; i >= 0; i--)
    {
      Segment seg = rcv_buf.get(i);
      if (seg.sn == sn)
      {
        repeat = true;
        break;
      }
      if (_itimediff(sn, seg.sn) > 0)
      {
        after_idx = i;
        break;
      }
    }
    if (!repeat)
    {
      if (after_idx == -1)
      {
        rcv_buf.addFirst(newseg);
      } else
      {
        rcv_buf.add(after_idx + 1, newseg);
      }
    }
    // move available data from rcv_buf -> rcv_queue
    int count = 0;
    for (Segment seg : rcv_buf)
    {
      if (seg.sn == rcv_nxt && rcv_queue.size() < rcv_wnd)
      {
        rcv_queue.add(seg);
        rcv_nxt++;
        count++;
      } else
      {
        break;
      }
    }
    if (0 < count)
    {
      for (int i = 0; i < count; i++)
      {
        rcv_buf.removeFirst();
      }
    }
  }

  /**
   *
   * when you received a low level packet (eg. UDP packet), call it
   *
   * @param data
   * @return
   */
  public int input(ByteBuf data)
  {
    int s_una = snd_una;
    if (data==null||data.readableBytes() < IKCP_OVERHEAD)
    {
      return -1;
    }
    int offset = 0;
    while (true)
    {
      int ts;
      int sn;
      int length;
      int una;
      int conv_;
      int wnd;
      byte cmd;
      byte frg;
      if (data.readableBytes() < IKCP_OVERHEAD)
      {
        break;
      }
      conv_ = data.readInt();
      offset += 4;
      if (conv != conv_)
      {
        return -1;
      }
      cmd = data.readByte();
      offset += 1;
      frg = data.readByte();
      offset += 1;
      wnd = data.readShort();
      offset += 2;
      ts = data.readInt();
      offset += 4;
      sn = data.readInt();
      offset += 4;
      una = data.readInt();
      offset += 4;
      length = data.readInt();
      offset += 4;
      if (data.readableBytes() < length)
      {
        return -2;
      }
      switch ((int) cmd)
      {
        case IKCP_CMD_PUSH:
        case IKCP_CMD_ACK:
        case IKCP_CMD_WASK:
        case IKCP_CMD_WINS:
          break;
        default:
          return -3;
      }
      rmt_wnd = wnd & 0x0000ffff;
      parse_una(una);
      shrink_buf();
      switch (cmd)
      {
        case IKCP_CMD_ACK:
          if (_itimediff(current, ts) >= 0)
          {
            update_ack(_itimediff(current, ts));
          }
          parse_ack(sn);
          shrink_buf();
          break;
        case IKCP_CMD_PUSH:
          if (_itimediff(sn, rcv_nxt + rcv_wnd) < 0)
          {
            ack_push(sn, ts);
            if (_itimediff(sn, rcv_nxt) >= 0)
            {
              Segment seg = new Segment(length);
              seg.conv = conv_;
              seg.cmd = cmd;
              seg.frg = frg & 0x000000ff;
              seg.wnd = wnd;
              seg.ts = ts;
              seg.sn = sn;
              seg.una = una;
              if (length > 0)
              {
                seg.data.writeBytes(data, length);
              }
              parse_data(seg);
            }
          }
          break;
        case IKCP_CMD_WASK:
          // ready to send back IKCP_CMD_WINS in Ikcp_flush
          // tell remote my window size
          probe |= IKCP_ASK_TELL;
          break;
        // do nothing
        case IKCP_CMD_WINS:
          break;
        default:
          return -3;
      }
      offset += length;
    }
    if (_itimediff(snd_una, s_una) > 0)
    {
      if (cwnd < rmt_wnd)
      {
        int mss_ = mss;
        if (cwnd < ssthresh)
        {
          cwnd++;
          incr += mss_;
        } else
        {
          if (incr < mss_)
          {
            incr = mss_;
          }
          incr += (mss_ * mss_) / incr + (mss_ / 16);
          if ((cwnd + 1) * mss_ <= incr)
          {
            cwnd++;
          }
        }
        if (cwnd > rmt_wnd)
        {
          cwnd = rmt_wnd;
          incr = rmt_wnd * mss_;
        }
      }
    }
    return 0;
  }

  private int wnd_unused()
  {
    if (rcv_queue.size() < rcv_wnd)
    {
      return rcv_wnd - rcv_queue.size();
    }
    return 0;
  }

  /**
   * flush pending data
   */
  private void flush()
  {
    int cur = current;
    int change = 0;
    int lost = 0;
    if (0 == updated)
    {
      return;
    }
    Segment seg = new Segment(0);
    seg.conv = conv;
    seg.cmd = IKCP_CMD_ACK;
    seg.wnd = wnd_unused();
    seg.una = rcv_nxt;
    // flush acknowledges
    int count = acklist.size() / 2;
    int offset = 0;
    for (int i = 0; i < count; i++)
    {
      if (offset + IKCP_OVERHEAD > mtu)
      {
        this.output.out(buffer, this, user);
        offset = 0;
        buffer = PooledByteBufAllocator.DEFAULT.buffer((mtu + IKCP_OVERHEAD) * 3);
      }
      seg.sn = acklist.get(i * 2 + 0);
      seg.ts = acklist.get(i * 2 + 1);
      offset += seg.encode(buffer);
    }
    acklist.clear();
    // probe window size (if remote window size equals zero)
    if (0 == rmt_wnd)
    {
      if (0 == probe_wait)
      {
        probe_wait = IKCP_PROBE_INIT;
        ts_probe = current + probe_wait;
      } else if (_itimediff(current, ts_probe) >= 0)
      {
        if (probe_wait < IKCP_PROBE_INIT)
        {
          probe_wait = IKCP_PROBE_INIT;
        }
        probe_wait += probe_wait / 2;
        if (probe_wait > IKCP_PROBE_LIMIT)
        {
          probe_wait = IKCP_PROBE_LIMIT;
        }
        ts_probe = current + probe_wait;
        probe |= IKCP_ASK_SEND;
      }
    } else
    {
      ts_probe = 0;
      probe_wait = 0;
    }
    // flush window probing commands
    if ((probe & IKCP_ASK_SEND) != 0)
    {
      seg.cmd = IKCP_CMD_WASK;
      if (offset + IKCP_OVERHEAD > mtu)
      {
        this.output.out(buffer, this, user);
        offset = 0;
        buffer = PooledByteBufAllocator.DEFAULT.buffer((mtu + IKCP_OVERHEAD) * 3);
      }
      offset += seg.encode(buffer);
    }
    probe = 0;
    // calculate window size
    int cwnd_ = Math.min(snd_wnd, rmt_wnd);
    if (0 == nocwnd)
    {
      cwnd_ = Math.min(cwnd, cwnd_);
    }
    count = 0;
    for (Segment snd_queue1 : snd_queue)
    {
      if (_itimediff(snd_nxt, snd_una + cwnd_) >= 0)
      {
        break;
      }
      Segment newseg = snd_queue1;
      newseg.conv = conv;
      newseg.cmd = IKCP_CMD_PUSH;
      newseg.wnd = seg.wnd;
      newseg.ts = cur;
      newseg.sn = snd_nxt;
      newseg.una = rcv_nxt;
      newseg.resendts = cur;
      newseg.rto = rx_rto;
      newseg.fastack = 0;
      newseg.xmit = 0;
      snd_buf.add(newseg);
      snd_nxt++;
      count++;
    }
    if (0 < count)
    {
      for (int i = 0; i < count; i++)
      {
        snd_queue.removeFirst();
      }
    }
    // calculate resent
    int resent = fastresend;
    if (fastresend <= 0)
    {
      resent = 0xffffffff;
    }
    int rtomin = rx_rto >> 3;
    if (nodelay != 0)
    {
      rtomin = 0;
    }
    // flush data segments
    for (Segment segment : snd_buf)
    {
      boolean needsend = false;
      //int debug = _itimediff(cur, segment.resendts);
      if (0 == segment.xmit)
      {
        needsend = true;
        segment.xmit++;
        segment.rto = rx_rto;
        segment.resendts = cur + segment.rto + rtomin;
      } else if (_itimediff(cur, segment.resendts) >= 0)
      {
        needsend = true;
        segment.xmit++;
        xmit++;
        if (0 == nodelay)
        {
          segment.rto += rx_rto;
        } else
        {
          segment.rto += rx_rto / 2;
        }
        segment.resendts = cur + segment.rto;
        lost = 1;
      } else if (segment.fastack >= resent)
      {
        needsend = true;
        segment.xmit++;
        segment.fastack = 0;
        segment.resendts = cur + segment.rto;
        change++;
      }
      if (needsend)
      {
        segment.ts = cur;
        segment.wnd = seg.wnd;
        segment.una = rcv_nxt;
        int need = IKCP_OVERHEAD + segment.data.readableBytes();
        if (offset + need >= mtu)
        {
          this.output.out(buffer, this, user);
          buffer = PooledByteBufAllocator.DEFAULT.buffer((mtu + IKCP_OVERHEAD) * 3);
          offset = 0;
        }
        offset += segment.encode(buffer);
        if (segment.data.readableBytes() > 0)
        {
          offset += segment.data.readableBytes();
          buffer.writeBytes(segment.data);
        }
        if (segment.xmit >= dead_link)
        {
          state = 0;
        }
      }
    }
    // flash remain segments
    if (offset > 0)
    {
      this.output.out(buffer, this, user);
      buffer = PooledByteBufAllocator.DEFAULT.buffer((mtu + IKCP_OVERHEAD) * 3);
    }
    // update ssthresh
    if (change != 0)
    {
      int inflight = snd_nxt - snd_una;
      ssthresh = inflight / 2;
      if (ssthresh < IKCP_THRESH_MIN)
      {
        ssthresh = IKCP_THRESH_MIN;
      }
      cwnd = ssthresh + resent;
      incr = cwnd * mss;
    }
    if (lost != 0)
    {
      ssthresh = cwnd / 2;
      if (ssthresh < IKCP_THRESH_MIN)
      {
        ssthresh = IKCP_THRESH_MIN;
      }
      cwnd = 1;
      incr = mss;
    }
    if (cwnd < 1)
    {
      cwnd = 1;
      incr = mss;
    }
  }

  /**
   * update state (call it repeatedly, every 10ms-100ms), or you can ask
   * ikcp_check when to call it again (without ikcp_input/_send calling).
   *
   * @param current current timestamp in millisec.
   */
  public void update(long current)
  {
    this.current = (int) current;
    if (0 == updated)
    {
      updated = 1;
      ts_flush = this.current;
    }
    int slap = _itimediff(this.current, ts_flush);
    if (slap >= 10000 || slap < -10000)
    {
      ts_flush = this.current;
      slap = 0;
    }
    if (slap >= 0)
    {
      ts_flush += interval;
      if (_itimediff(this.current, ts_flush) >= 0)
      {
        ts_flush = this.current + interval;
      }
      flush();
    }
  }

  /**
   * Determine when should you invoke ikcp_update: returns when you should
   * invoke ikcp_update in millisec, if there is no ikcp_input/_send calling.
   * you can call ikcp_update in that time, instead of call update repeatly.
   * Important to reduce unnacessary ikcp_update invoking. use it to schedule
   * ikcp_update (eg. implementing an epoll-like mechanism, or optimize
   * ikcp_update when handling massive kcp connections)
   *
   * @param current
   * @return
   */
  public int check(long current)
  {
    int cur = (int) current;
    if (0 == updated)
    {
      return cur;
    }
    int ts_flush_temp = this.ts_flush;
    int tm_packet = 0x7fffffff;
    if (_itimediff(cur, ts_flush_temp) >= 10000 || _itimediff(cur, ts_flush_temp) < -10000)
    {
      ts_flush_temp = cur;
    }
    if (_itimediff(cur, ts_flush_temp) >= 0)
    {
      return cur;
    }
    int tm_flush = _itimediff(ts_flush_temp, cur);
    for (Segment seg : snd_buf)
    {
      int diff = _itimediff(seg.resendts, cur);
      if (diff <= 0)
      {
        return cur;
      }
      if (diff < tm_packet)
      {
        tm_packet = diff;
      }
    }
    int minimal = tm_packet < tm_flush ? tm_packet : tm_flush;
    if (minimal >= interval)
    {
      minimal = interval;
    }
    return cur + minimal;
  }

  /**
   * change MTU size, default is 1400
   *
   * @param mtu
   * @return
   */
  public int setMtu(int mtu)
  {
    if (mtu < 50 || mtu < IKCP_OVERHEAD)
    {
      return -1;
    }
    ByteBuf buf = PooledByteBufAllocator.DEFAULT.buffer((mtu + IKCP_OVERHEAD) * 3);
    this.mtu = mtu;
    mss = mtu - IKCP_OVERHEAD;
    if (buffer != null)
    {
      buffer.release();
    }
    this.buffer = buf;
    return 0;
  }

  /**
   * interval per update
   *
   * @param interval
   * @return
   */
  public int interval(int interval)
  {
    if (interval > 5000)
    {
      interval = 5000;
    } else if (interval < 10)
    {
      interval = 10;
    }
    this.interval = interval;
    return 0;
  }

  /**
   * fastest: ikcp_nodelay(kcp, 1, 20, 2, 1) nodelay: 0:disable(default),
   * 1:enable interval: internal update timer interval in millisec, default is
   * 100ms resend: 0:disable fast resend(default), 1:enable fast resend nc:
   * 0:normal congestion control(default), 1:disable congestion control
   *
   * @param nodelay
   * @param interval
   * @param resend
   * @param nc
   * @return
   */
  public int noDelay(int nodelay, int interval, int resend, int nc)
  {
    if (nodelay >= 0)
    {
      this.nodelay = nodelay;
      if (nodelay != 0)
      {
        rx_minrto = IKCP_RTO_NDL;
      } else
      {
        rx_minrto = IKCP_RTO_MIN;
      }
    }
    if (interval >= 0)
    {
      if (interval > 5000)
      {
        interval = 5000;
      } else if (interval < 10)
      {
        interval = 10;
      }
      this.interval = interval;
    }
    if (resend >= 0)
    {
      fastresend = resend;
    }
    if (nc >= 0)
    {
      nocwnd = nc;
    }
    return 0;
  }

  /**
   * set maximum window size: sndwnd=32, rcvwnd=32 by default
   *
   * @param sndwnd
   * @param rcvwnd
   * @return
   */
  public int wndSize(int sndwnd, int rcvwnd)
  {
    if (sndwnd > 0)
    {
      snd_wnd = sndwnd;
    }
    if (rcvwnd > 0)
    {
      rcv_wnd = rcvwnd;
    }
    return 0;
  }

  /**
   * get how many packet is waiting to be sent
   *
   * @return
   */
  public int waitSnd()
  {
    return snd_buf.size() + snd_queue.size();
  }

  public void setNextUpdate(int nextUpdate)
  {
    this.nextUpdate = nextUpdate;
  }

  public int getNextUpdate()
  {
    return nextUpdate;
  }

  public Object getUser()
  {
    return user;
  }

  @Override
  public String toString()
  {
    return this.user.toString();
  }

}
