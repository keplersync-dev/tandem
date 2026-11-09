/*
 * A Java port of KCP (https://github.com/skywind3000/kcp), a small reliable protocol over UDP.
 * Copyright (c) 2017 Lin Wei (skywind3000 at gmail.com). MIT License: see licenses/KCP-MIT.txt.
 * Ported for Tandem in stream mode, with a higher starting slow-start threshold, the window grown per segment
 * acknowledged, and cut once per loss event.
 */
package app.keplersync.tandem.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * KCP: turns lossy, reordered UDP datagrams into one reliable, ordered byte stream. The game's TCP stream rides on it
 * when two games punched through to each other. Not thread-safe: callers hold its lock (Punch does).
 *
 * Sequence numbers and clocks are 32-bit and wrap; they're only ever compared through [diff].
 */
final class Kcp {
	/** Sends one datagram's worth of KCP. */
	interface Output {
		void output(byte[] data, int len);
	}

	static final int RTO_NDL = 30, RTO_MIN = 100, RTO_DEF = 200, RTO_MAX = 60000;
	static final int CMD_PUSH = 81, CMD_ACK = 82, CMD_WASK = 83, CMD_WINS = 84;
	static final int ASK_SEND = 1, ASK_TELL = 2;
	static final int WND_SND = 32, WND_RCV = 128;
	static final int MTU_DEF = 1400, OVERHEAD = 24, INTERVAL = 100, DEADLINK = 20;
	/** ikcp starts at 2; a join's few megabytes of chunks would then grow the window one segment at a time. */
	static final int THRESH_INIT = 64, THRESH_MIN = 2;
	static final int PROBE_INIT = 7000, PROBE_LIMIT = 120000, FASTACK_LIMIT = 5;

	static final class Segment {
		int conv, cmd, frg, wnd, ts, sn, una, resendts, rto, fastack, xmit;
		final byte[] data;

		Segment(int size) {
			data = new byte[size];
		}
	}

	final int conv;
	private final Output output;
	int mtu = MTU_DEF, mss = MTU_DEF - OVERHEAD, state;
	int sndUna, sndNxt, rcvNxt;
	int ssthresh = THRESH_INIT;
	int rxRttval, rxSrtt, rxRto = RTO_DEF, rxMinrto = RTO_MIN;
	int sndWnd = WND_SND, rcvWnd = WND_RCV, rmtWnd = WND_RCV, cwnd, probe;
	int current, interval = INTERVAL, tsFlush = INTERVAL, xmit;
	int nodelay;
	boolean updated;
	int tsProbe, probeWait;
	int deadLink = DEADLINK, incr;
	final ArrayDeque<Segment> sndQueue = new ArrayDeque<>();
	final ArrayDeque<Segment> rcvQueue = new ArrayDeque<>();
	final List<Segment> sndBuf = new ArrayList<>();
	final List<Segment> rcvBuf = new ArrayList<>();
	private int[] acklist = new int[16];
	private int ackcount;
	private byte[] buffer = new byte[(MTU_DEF + OVERHEAD) * 3];
	int fastresend;
	/** The next segment sent when the window was last cut: losses before it belong to that same event. */
	int recover;
	int fastlimit = FASTACK_LIMIT;
	boolean nocwnd;

	Kcp(int conv, Output output) {
		this.conv = conv;
		this.output = output;
	}

	/** Starts both directions' sequence numbers at [sn] (tests: wrapping past 2^32). Both ends must agree. */
	void startAt(int sn) {
		sndUna = sndNxt = rcvNxt = recover = sn;
	}

	static int diff(int later, int earlier) {
		return later - earlier;
	}

	/** The next chunk received, into [out]; its length, or negative when there's nothing (or it doesn't fit). */
	int recv(byte[] out) {
		if (rcvQueue.isEmpty()) return -1;
		int peek = peekSize();
		if (peek < 0) return -2;
		if (peek > out.length) return -3;
		boolean recover = rcvQueue.size() >= rcvWnd;
		int len = 0;
		while (!rcvQueue.isEmpty()) {
			Segment seg = rcvQueue.poll();
			System.arraycopy(seg.data, 0, out, len, seg.data.length);
			len += seg.data.length;
			if (seg.frg == 0) break;
		}
		moveToQueue();
		if (rcvQueue.size() < rcvWnd && recover) probe |= ASK_TELL;
		return len;
	}

	int peekSize() {
		Segment first = rcvQueue.peek();
		if (first == null) return -1;
		if (first.frg == 0) return first.data.length;
		if (rcvQueue.size() < first.frg + 1) return -1;
		int len = 0;
		for (Segment seg : rcvQueue) {
			len += seg.data.length;
			if (seg.frg == 0) break;
		}
		return len;
	}

	/** Queues [len] bytes to send, as a stream: they may join the last queued segment. */
	int send(byte[] buf, int off, int len) {
		if (len < 0) return -1;
		Segment last = sndQueue.peekLast();
		if (last != null && last.data.length < mss) {
			int extend = Math.min(len, mss - last.data.length);
			Segment seg = new Segment(last.data.length + extend);
			System.arraycopy(last.data, 0, seg.data, 0, last.data.length);
			System.arraycopy(buf, off, seg.data, last.data.length, extend);
			sndQueue.pollLast();
			sndQueue.add(seg);
			off += extend;
			len -= extend;
		}
		if (len <= 0) return 0;
		int count = (len + mss - 1) / mss;
		if (count >= WND_RCV) return -2;
		for (int i = 0; i < count; i++) {
			int size = Math.min(len, mss);
			Segment seg = new Segment(size);
			System.arraycopy(buf, off, seg.data, 0, size);
			seg.frg = 0;
			sndQueue.add(seg);
			off += size;
			len -= size;
		}
		return 0;
	}

	private void updateAck(int rtt) {
		if (rxSrtt == 0) {
			rxSrtt = rtt;
			rxRttval = rtt / 2;
		} else {
			int delta = Math.abs(rtt - rxSrtt);
			rxRttval = (3 * rxRttval + delta) / 4;
			rxSrtt = (7 * rxSrtt + rtt) / 8;
			if (rxSrtt < 1) rxSrtt = 1;
		}
		int rto = rxSrtt + Math.max(interval, 4 * rxRttval);
		rxRto = Math.min(Math.max(rxMinrto, rto), RTO_MAX);
	}

	private void shrinkBuf() {
		sndUna = sndBuf.isEmpty() ? sndNxt : sndBuf.get(0).sn;
	}

	private void parseAck(int sn) {
		if (diff(sn, sndUna) < 0 || diff(sn, sndNxt) >= 0) return;
		for (int i = 0; i < sndBuf.size(); i++) {
			Segment seg = sndBuf.get(i);
			if (sn == seg.sn) {
				sndBuf.remove(i);
				break;
			}
			if (diff(sn, seg.sn) < 0) break;
		}
	}

	private void parseUna(int una) {
		int drop = 0;
		while (drop < sndBuf.size() && diff(una, sndBuf.get(drop).sn) > 0) drop++;
		if (drop > 0) sndBuf.subList(0, drop).clear();
	}

	private void parseFastack(int sn, int ts) {
		if (diff(sn, sndUna) < 0 || diff(sn, sndNxt) >= 0) return;
		for (Segment seg : sndBuf) {
			if (diff(sn, seg.sn) < 0) break;
			if (sn != seg.sn) seg.fastack++;
		}
	}

	private void ackPush(int sn, int ts) {
		if (ackcount * 2 + 2 > acklist.length) acklist = java.util.Arrays.copyOf(acklist, acklist.length * 2);
		acklist[ackcount * 2] = sn;
		acklist[ackcount * 2 + 1] = ts;
		ackcount++;
	}

	private void parseData(Segment seg) {
		int sn = seg.sn;
		if (diff(sn, rcvNxt + rcvWnd) >= 0 || diff(sn, rcvNxt) < 0) return;
		int i = rcvBuf.size() - 1;
		boolean repeat = false;
		for (; i >= 0; i--) {
			Segment s = rcvBuf.get(i);
			if (s.sn == sn) {
				repeat = true;
				break;
			}
			if (diff(sn, s.sn) > 0) break;
		}
		if (!repeat) rcvBuf.add(i + 1, seg);
		moveToQueue();
	}

	private void moveToQueue() {
		while (!rcvBuf.isEmpty()) {
			Segment seg = rcvBuf.get(0);
			if (seg.sn != rcvNxt || rcvQueue.size() >= rcvWnd) break;
			rcvBuf.remove(0);
			rcvQueue.add(seg);
			rcvNxt++;
		}
	}

	/** One datagram from the other end. 0, or negative when it isn't (wholly) this conversation's KCP. */
	int input(byte[] data, int off, int size) {
		int prevUna = sndUna;
		int maxack = 0, latestTs = 0;
		boolean flag = false;
		if (size < OVERHEAD) return -1;
		while (size >= OVERHEAD) {
			int conv = le32(data, off);
			int cmd = data[off + 4] & 0xFF;
			int frg = data[off + 5] & 0xFF;
			int wnd = (data[off + 6] & 0xFF) | (data[off + 7] & 0xFF) << 8;
			int ts = le32(data, off + 8);
			int sn = le32(data, off + 12);
			int una = le32(data, off + 16);
			int len = le32(data, off + 20);
			off += OVERHEAD;
			size -= OVERHEAD;
			if (conv != this.conv) return -1;
			if (len < 0 || size < len) return -2;
			if (cmd < CMD_PUSH || cmd > CMD_WINS) return -3;
			rmtWnd = wnd;
			parseUna(una);
			shrinkBuf();
			if (cmd == CMD_ACK) {
				if (diff(current, ts) >= 0) updateAck(diff(current, ts));
				parseAck(sn);
				shrinkBuf();
				if (!flag) {
					flag = true;
					maxack = sn;
					latestTs = ts;
				} else if (diff(sn, maxack) > 0) {
					maxack = sn;
					latestTs = ts;
				}
			} else if (cmd == CMD_PUSH) {
				if (diff(sn, rcvNxt + rcvWnd) < 0) {
					ackPush(sn, ts);
					if (diff(sn, rcvNxt) >= 0) {
						Segment seg = new Segment(len);
						System.arraycopy(data, off, seg.data, 0, len);
						seg.conv = conv;
						seg.cmd = cmd;
						seg.frg = frg;
						seg.wnd = wnd;
						seg.ts = ts;
						seg.sn = sn;
						seg.una = una;
						parseData(seg);
					}
				}
			} else if (cmd == CMD_WASK) {
				probe |= ASK_TELL;
			}
			off += len;
			size -= len;
		}
		if (flag) parseFastack(maxack, latestTs);
		// Grown for each segment acknowledged, as TCP does: ikcp grows once per datagram, and one datagram carries
		// dozens of acks, so after a loss it crawled back.
		int acked = diff(sndUna, prevUna);
		if (acked > 0 && cwnd < rmtWnd) {
			for (int i = 0; i < acked; i++) {
				if (cwnd < ssthresh) {
					cwnd++;
					incr += mss;
				} else {
					if (incr < mss) incr = mss;
					incr += (mss * mss) / incr + (mss / 16);
					if ((cwnd + 1) * mss <= incr) cwnd = (incr + mss - 1) / mss;
				}
			}
			if (cwnd > rmtWnd) {
				cwnd = rmtWnd;
				incr = rmtWnd * mss;
			}
		}
		return 0;
	}

	private int wndUnused() {
		return rcvQueue.size() < rcvWnd ? rcvWnd - rcvQueue.size() : 0;
	}

	private int encode(Segment seg, int ptr) {
		le32(buffer, ptr, seg.conv);
		buffer[ptr + 4] = (byte) seg.cmd;
		buffer[ptr + 5] = (byte) seg.frg;
		buffer[ptr + 6] = (byte) seg.wnd;
		buffer[ptr + 7] = (byte) (seg.wnd >> 8);
		le32(buffer, ptr + 8, seg.ts);
		le32(buffer, ptr + 12, seg.sn);
		le32(buffer, ptr + 16, seg.una);
		le32(buffer, ptr + 20, seg.data.length);
		return ptr + OVERHEAD;
	}

	private int flushIfFull(int ptr, int need) {
		if (ptr + need > mtu) {
			output.output(buffer, ptr);
			return 0;
		}
		return ptr;
	}

	void flush() {
		if (!updated) return;
		Segment seg = new Segment(0);
		seg.conv = conv;
		seg.cmd = CMD_ACK;
		seg.wnd = wndUnused();
		seg.una = rcvNxt;
		int ptr = 0;
		for (int i = 0; i < ackcount; i++) {
			ptr = flushIfFull(ptr, OVERHEAD);
			seg.sn = acklist[i * 2];
			seg.ts = acklist[i * 2 + 1];
			ptr = encode(seg, ptr);
		}
		ackcount = 0;
		if (rmtWnd == 0) {
			if (probeWait == 0) {
				probeWait = PROBE_INIT;
				tsProbe = current + probeWait;
			} else if (diff(current, tsProbe) >= 0) {
				if (probeWait < PROBE_INIT) probeWait = PROBE_INIT;
				probeWait += probeWait / 2;
				if (probeWait > PROBE_LIMIT) probeWait = PROBE_LIMIT;
				tsProbe = current + probeWait;
				probe |= ASK_SEND;
			}
		} else {
			tsProbe = 0;
			probeWait = 0;
		}
		if ((probe & ASK_SEND) != 0) {
			seg.cmd = CMD_WASK;
			ptr = flushIfFull(ptr, OVERHEAD);
			ptr = encode(seg, ptr);
		}
		if ((probe & ASK_TELL) != 0) {
			seg.cmd = CMD_WINS;
			ptr = flushIfFull(ptr, OVERHEAD);
			ptr = encode(seg, ptr);
		}
		probe = 0;

		int cwndNow = Math.min(sndWnd, rmtWnd);
		if (!nocwnd) cwndNow = Math.min(cwnd, cwndNow);
		while (diff(sndNxt, sndUna + cwndNow) < 0 && !sndQueue.isEmpty()) {
			Segment s = sndQueue.poll();
			s.conv = conv;
			s.cmd = CMD_PUSH;
			s.wnd = seg.wnd;
			s.ts = current;
			s.sn = sndNxt++;
			s.una = rcvNxt;
			s.resendts = current;
			s.rto = rxRto;
			s.fastack = 0;
			s.xmit = 0;
			sndBuf.add(s);
		}
		int resent = fastresend > 0 ? fastresend : Integer.MAX_VALUE;
		int rtomin = nodelay == 0 ? rxRto >> 3 : 0;
		boolean change = false, lost = false;
		for (Segment s : sndBuf) {
			boolean needsend = false;
			if (s.xmit == 0) {
				needsend = true;
				s.xmit++;
				s.rto = rxRto;
				s.resendts = current + s.rto + rtomin;
			} else if (diff(current, s.resendts) >= 0) {
				needsend = true;
				s.xmit++;
				xmit++;
				if (nodelay == 0) s.rto += Math.max(s.rto, rxRto);
				else s.rto += (nodelay < 2 ? s.rto : rxRto) / 2;
				s.resendts = current + s.rto;
				lost = true;
			} else if (s.fastack >= resent && (s.xmit <= fastlimit || fastlimit <= 0)) {
				needsend = true;
				s.xmit++;
				s.fastack = 0;
				s.resendts = current + s.rto;
				change = true;
			}
			if (needsend) {
				s.ts = current;
				s.wnd = seg.wnd;
				s.una = rcvNxt;
				ptr = flushIfFull(ptr, OVERHEAD + s.data.length);
				ptr = encode(s, ptr);
				System.arraycopy(s.data, 0, buffer, ptr, s.data.length);
				ptr += s.data.length;
				if (s.xmit >= deadLink) state = -1;
			}
		}
		if (ptr > 0) output.output(buffer, ptr);
		// One cut per loss event, as TCP does: a full router queue drops many segments at once, and ikcp cut again
		// for each, down to nothing. Losses among what was already sent when the last cut happened don't count again.
		if ((change || lost) && diff(sndUna, recover) >= 0) {
			recover = sndNxt;
			if (lost) {
				ssthresh = Math.max(cwndNow / 2, THRESH_MIN);
				cwnd = 1;
				incr = mss;
			} else {
				int inflight = sndNxt - sndUna;
				ssthresh = Math.max(inflight / 2, THRESH_MIN);
				cwnd = ssthresh + resent;
				incr = cwnd * mss;
			}
		}
		if (cwnd < 1) {
			cwnd = 1;
			incr = mss;
		}
	}

	/** Advances the clock to [current] (milliseconds) and sends what's due. Call every few milliseconds. */
	void update(int current) {
		this.current = current;
		if (!updated) {
			updated = true;
			tsFlush = current;
		}
		int slap = diff(current, tsFlush);
		if (slap >= 10000 || slap < -10000) {
			tsFlush = current;
			slap = 0;
		}
		if (slap >= 0) {
			tsFlush += interval;
			if (diff(current, tsFlush) >= 0) tsFlush = current + interval;
			flush();
		}
	}

	void setMtu(int mtu) {
		if (mtu < 50 || mtu < OVERHEAD) throw new IllegalArgumentException("mtu");
		buffer = new byte[(mtu + OVERHEAD) * 3];
		this.mtu = mtu;
		mss = mtu - OVERHEAD;
	}

	void nodelay(int nodelay, int interval, int resend, boolean nc) {
		this.nodelay = nodelay;
		rxMinrto = nodelay != 0 ? RTO_NDL : RTO_MIN;
		this.interval = Math.max(10, Math.min(5000, interval));
		fastresend = resend;
		nocwnd = nc;
	}

	void wndSize(int snd, int rcv) {
		if (snd > 0) sndWnd = snd;
		if (rcv > 0) rcvWnd = Math.max(rcv, WND_RCV);
	}

	/** Segments waiting to be sent or acknowledged. */
	int waitSnd() {
		return sndBuf.size() + sndQueue.size();
	}

	static int le32(byte[] b, int off) {
		return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8 | (b[off + 2] & 0xFF) << 16 | (b[off + 3] & 0xFF) << 24;
	}

	static void le32(byte[] b, int off, int v) {
		b[off] = (byte) v;
		b[off + 1] = (byte) (v >> 8);
		b[off + 2] = (byte) (v >> 16);
		b[off + 3] = (byte) (v >> 24);
	}
}
