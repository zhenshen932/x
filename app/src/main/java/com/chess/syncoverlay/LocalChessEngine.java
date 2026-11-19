package com.chess.syncoverlay;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * High-strength zero-allocation native ARM64 chess engine.
 * Features: PeSTO Tapered Eval, Full Quiescence Search, 64-bit Zobrist Transposition Table,
 * Iterative Deepening, Null-Move Pruning, Late Move Reductions, Check Extensions,
 * Killer Moves, History Heuristic, MVV-LVA Ordering, and Endgame Mop-Up Evaluation.
 */
public class LocalChessEngine {
    public static class SearchResult {
        public final String uci;
        public final int depth;
        public final int score;

        public SearchResult(String uci, int depth, int score) {
            this.uci = uci;
            this.depth = depth;
            this.score = score;
        }
    }

    private static final int EMPTY = 0;
    private static final int PAWN = 1, KNIGHT = 2, BISHOP = 3, ROOK = 4, QUEEN = 5, KING = 6;
    private static final int WHITE = 0, BLACK = 1;

    private static final int FLAG_NONE = 0;
    private static final int FLAG_EP = 1;
    private static final int FLAG_CASTLE = 2;
    private static final int FLAG_DOUBLE_PAWN = 3;

    private static final int INF = 32000;
    private static final int MATE = 30000;
    private static final int MAX_PLY = 64;

    private static final int TT_SIZE = 1 << 19; // 524,288 entries (~12 MB)
    private static final int TT_MASK = TT_SIZE - 1;
    private static final int TT_EXACT = 1;
    private static final int TT_ALPHA = 2;
    private static final int TT_BETA = 3;

    // Castling rights masks per square
    private static final int[] CASTLE_MASK = new int[64];
    static {
        Arrays.fill(CASTLE_MASK, 15);
        CASTLE_MASK[0] &= ~8;   // a8
        CASTLE_MASK[4] &= ~12;  // e8
        CASTLE_MASK[7] &= ~4;   // h8
        CASTLE_MASK[56] &= ~2;  // a1
        CASTLE_MASK[60] &= ~3;  // e1
        CASTLE_MASK[63] &= ~1;  // h1
    }

    // Zobrist keys
    private static final long[][] Z_PIECE = new long[16][64];
    private static final long[] Z_CASTLE = new long[16];
    private static final long[] Z_EP = new long[65];
    private static final long Z_SIDE;

    static {
        long seed = 0x9E3779B97F4A7C15L;
        for (int p = 0; p < 16; p++) {
            for (int sq = 0; sq < 64; sq++) {
                seed = nextRand(seed);
                Z_PIECE[p][sq] = seed;
            }
        }
        for (int i = 0; i < 16; i++) {
            seed = nextRand(seed);
            Z_CASTLE[i] = seed;
        }
        for (int i = 0; i < 65; i++) {
            seed = nextRand(seed);
            Z_EP[i] = seed;
        }
        seed = nextRand(seed);
        Z_SIDE = seed;
    }

    private static long nextRand(long x) {
        x ^= (x >>> 12);
        x ^= (x << 25);
        x ^= (x >>> 27);
        return x * 2685821657736338717L;
    }

    // PeSTO Tapered Evaluation Values & Piece-Square Tables (a8=0 .. h1=63)
    private static final int[] MG_VAL = {0, 82, 337, 365, 477, 1025, 0};
    private static final int[] EG_VAL = {0, 94, 281, 297, 512, 936, 0};
    private static final int[] PHASE_INC = {0, 0, 1, 1, 2, 4, 0};
    private static final int[] MVV_VAL = {0, 100, 320, 330, 500, 900, 10000};

    private static final int[] MG_PAWN = {
          0,   0,   0,   0,   0,   0,  0,   0,
         98, 134,  61,  95,  68, 126, 34, -11,
         -6,   7,  26,  31,  65,  56, 25, -20,
        -14,  13,   6,  21,  23,  12, 17, -23,
        -27,  -2,  -5,  12,  17,   6, 10, -25,
        -26,  -4,  -4, -10,   3,   3, 33, -12,
        -35,  -1, -20, -23, -15,  24, 38, -22,
          0,   0,   0,   0,   0,   0,  0,   0
    };
    private static final int[] EG_PAWN = {
          0,   0,   0,   0,   0,   0,   0,   0,
        178, 173, 158, 134, 147, 132, 165, 187,
         94, 100,  85,  67,  56,  53,  82,  84,
         32,  24,  13,   5,  -2,   4,  17,  17,
         13,   9,  -3,  -7,  -7,  -8,   3,  -1,
          4,   7,  -6,   1,   0,  -5,  -1,  -8,
         13,   8,   8,  10,  13,   0,   2,  -7,
          0,   0,   0,   0,   0,   0,   0,   0
    };
    private static final int[] MG_KNIGHT = {
        -167, -89, -34, -49,  61, -97, -15, -107,
         -73, -41,  72,  36,  23,  62,   7,  -17,
         -47,  60,  37,  65,  84, 129,  73,   44,
          -9,  17,  19,  53,  37,  69,  18,   22,
         -13,   4,  16,  13,  28,  19,  21,   -8,
         -23,  -9,  12,  10,  19,  17,  25,  -16,
         -29, -53, -12,  -3,  -1,  18, -14,  -19,
        -105, -21, -58, -33, -17, -28, -19,  -23
    };
    private static final int[] EG_KNIGHT = {
        -58, -38, -13, -28, -31, -27, -63, -99,
        -25,  -8, -25,  -2,  -9, -25, -24, -52,
        -24, -20,  10,   9,  -1,  -9, -19, -41,
        -17,   3,  22,  22,  22,  11,   8, -18,
        -18,  -6,  16,  25,  16,  17,   4, -18,
        -23,  -3,  -1,  15,  10,  -3, -20, -22,
        -42, -20, -10,  -5,  -2, -20, -23, -44,
        -29, -51, -23, -15, -22, -18, -50, -64
    };
    private static final int[] MG_BISHOP = {
        -29,   4, -82, -37, -25, -42,   7,  -8,
        -26,  16, -18, -13,  30,  59,  18, -47,
        -16,  37,  43,  40,  35,  50,  37,  -2,
         -4,   5,  19,  50,  37,  37,   7,  -2,
         -6,  13,  13,  26,  34,  12,  10,   4,
          0,  15,  15,  15,  14,  27,  18,  10,
          4,  15,  16,   0,   7,  21,  33,   1,
        -33,  -3, -14, -21, -13, -12, -39, -21
    };
    private static final int[] EG_BISHOP = {
        -14, -21, -11,  -8, -7,  -9, -17, -24,
         -8,  -4,   7, -12, -3, -13,  -4, -14,
          2,  -8,   0,  -1, -2,   6,   0,   4,
         -3,   9,  12,   9, 14,  10,   3,   2,
         -6,   3,  13,  19,  7,  10,  -3,  -9,
        -12,  -3,   8,  10, 13,   3,  -7, -15,
        -14, -18,  -7,  -1,  4,  -9, -15, -27,
        -23,  -9, -23,  -5, -9, -16,  -5, -17
    };
    private static final int[] MG_ROOK = {
         32,  42,  32,  51, 63,  9,  31,  43,
         27,  32,  58,  62, 80, 67,  26,  44,
         -5,  19,  26,  36, 17, 45,  61,  16,
        -24, -11,   7,  26, 24, 35,  -8, -20,
        -36, -26, -12,  -1,  9, -7,   6, -23,
        -45, -25, -16, -17,  3,  0,  -5, -33,
        -44, -16, -20,  -9, -1, 11,  -6, -71,
        -19, -13,   1,  17, 16,  7, -37, -26
    };
    private static final int[] EG_ROOK = {
         13, 10, 18, 15, 12,  12,   8,   5,
         11, 13, 13, 11, -3,   3,   8,   3,
          7,  7,  7,  5,  4,  -3,  -5,  -3,
          4,  3, 13,  1,  2,   1,  -1,   2,
          3,  5,  8,  4, -5,  -6,  -8, -11,
         -4,  0, -5, -1, -7, -12,  -8, -16,
         -6, -6,  0,  2, -9,  -9, -11,  -3,
         -9,  2,  3, -1, -5, -13,   4, -20
    };
    private static final int[] MG_QUEEN = {
        -28,   0,  29,  12,  59,  44,  43,  45,
        -24, -39,  -5,   1, -16,  57,  28,  54,
        -13, -17,   7,   8,  29,  56,  47,  57,
        -27, -27, -16, -16,  -1,  17,  -2,   1,
         -9, -26,  -9, -10,  -2,  -4,   3,  -3,
        -14,   2, -11,  -2,  -5,   2,  14,   5,
        -35,  -8,  11,   2,   8,  15,  -3,   1,
         -1, -18,  -9,  10, -15, -25, -31, -50
    };
    private static final int[] EG_QUEEN = {
         -9,  22,  22,  27,  27,  19,  10,  20,
        -17,  20,  32,  41,  58,  25,  30,   0,
        -20,   6,   9,  49,  47,  35,  19,   9,
          3,  22,  24,  45,  57,  40,  57,  36,
        -18,  28,  19,  47,  31,  34,  39,  23,
        -16, -27,  15,   6,   9,  17,  10,   5,
        -22, -23, -30, -16, -16, -23, -36, -32,
        -33, -28, -22, -43,  -5, -32, -20, -41
    };
    private static final int[] MG_KING = {
        -65,  23,  16, -15, -56, -34,   2,  13,
         29,  -1, -20,  -7,  -8,  -4, -38, -29,
         -9,  24,   2, -16, -20,   6,  22, -22,
        -17, -20, -12, -27, -30, -25, -14, -36,
        -49,  -1, -27, -39, -46, -44, -33, -51,
        -14, -14, -22, -46, -44, -30, -15, -27,
          1,   7,  -8, -64, -43, -16,   9,   8,
        -15,  36,  12, -54,   8, -28,  24,  14
    };
    private static final int[] EG_KING = {
        -74, -35, -18, -18, -11,  15,   4, -17,
        -12,  17,  14,  17,  17,  38,  23,  11,
         10,  17,  23,  15,  20,  45,  44,  13,
         -8,  22,  24,  27,  26,  33,  26,   3,
        -18,  -4,  21,  24,  27,  23,   9, -11,
        -19,  -3,  11,  21,  23,  16,   7,  -9,
        -27, -11,   4,  13,  14,   4,  -5, -17,
        -53, -34, -21, -11, -28, -14, -24, -43
    };

    private static final int[][] MG_PST = {null, MG_PAWN, MG_KNIGHT, MG_BISHOP, MG_ROOK, MG_QUEEN, MG_KING};
    private static final int[][] EG_PST = {null, EG_PAWN, EG_KNIGHT, EG_BISHOP, EG_ROOK, EG_QUEEN, EG_KING};

    private static final int[][] KNIGHT_OFF = {{-2,-1},{-2,1},{-1,-2},{-1,2},{1,-2},{1,2},{2,-1},{2,1}};
    private static final int[][] KING_OFF = {{-1,-1},{-1,0},{-1,1},{0,-1},{0,1},{1,-1},{1,0},{1,1}};
    private static final int[][] ROOK_DIR = {{-1,0},{1,0},{0,-1},{0,1}};
    private static final int[][] BISHOP_DIR = {{-1,-1},{-1,1},{1,-1},{1,1}};

    // Board state
    private final int[] board = new int[64];
    private final int[] kingSq = new int[2];
    private int side = WHITE;
    private int castling = 15;
    private int epSq = -1;
    private long hash = 0L;

    // Undo stack (zero allocation during search)
    private final int[] uCap = new int[128];
    private final int[] uCastle = new int[128];
    private final int[] uEp = new int[128];
    private final long[] uHash = new long[128];
    private int plyIdx = 0;

    // Transposition Table (reused across searches)
    private final long[] ttKey = new long[TT_SIZE];
    private final int[] ttMove = new int[TT_SIZE];
    private final short[] ttScore = new short[TT_SIZE];
    private final byte[] ttDepth = new byte[TT_SIZE];
    private final byte[] ttFlag = new byte[TT_SIZE];

    // Move buffers & ordering heuristics
    private final int[][] moveBuf = new int[MAX_PLY + 8][256];
    private final int[][] scoreBuf = new int[MAX_PLY + 8][256];
    private final int[][] killers = new int[MAX_PLY + 8][2];
    private final int[][][] history = new int[2][64][64];

    private long deadlineMs;
    private long nodes;
    private boolean timeUp;
    private AtomicBoolean externalStop;

    private static int packMove(int from, int to, int promo, int flag) {
        return from | (to << 6) | (promo << 12) | (flag << 15);
    }

    private static int moveFrom(int m) { return m & 63; }
    private static int moveTo(int m) { return (m >>> 6) & 63; }
    private static int movePromo(int m) { return (m >>> 12) & 7; }
    private static int moveFlag(int m) { return (m >>> 15) & 7; }

    public static String moveToUci(int m) {
        int from = moveFrom(m);
        int to = moveTo(m);
        int promo = movePromo(m);
        StringBuilder sb = new StringBuilder(5);
        sb.append((char) ('a' + (from & 7)));
        sb.append((char) ('8' - (from >> 3)));
        sb.append((char) ('a' + (to & 7)));
        sb.append((char) ('8' - (to >> 3)));
        if (promo == QUEEN) sb.append('q');
        else if (promo == ROOK) sb.append('r');
        else if (promo == BISHOP) sb.append('b');
        else if (promo == KNIGHT) sb.append('n');
        return sb.toString();
    }

    public boolean loadFen(String fen) {
        Arrays.fill(board, EMPTY);
        kingSq[WHITE] = -1;
        kingSq[BLACK] = -1;
        plyIdx = 0;

        String[] parts = fen.trim().split("\\s+");
        if (parts.length < 4) return false;

        int sq = 0;
        String rows = parts[0];
        for (int i = 0; i < rows.length() && sq < 64; i++) {
            char ch = rows.charAt(i);
            if (ch == '/') continue;
            if (ch >= '1' && ch <= '8') {
                sq += (ch - '0');
            } else {
                int p = EMPTY;
                switch (ch) {
                    case 'P': p = PAWN; break;
                    case 'N': p = KNIGHT; break;
                    case 'B': p = BISHOP; break;
                    case 'R': p = ROOK; break;
                    case 'Q': p = QUEEN; break;
                    case 'K': p = KING; kingSq[WHITE] = sq; break;
                    case 'p': p = 8 | PAWN; break;
                    case 'n': p = 8 | KNIGHT; break;
                    case 'b': p = 8 | BISHOP; break;
                    case 'r': p = 8 | ROOK; break;
                    case 'q': p = 8 | QUEEN; break;
                    case 'k': p = 8 | KING; kingSq[BLACK] = sq; break;
                }
                board[sq++] = p;
            }
        }
        if (kingSq[WHITE] < 0 || kingSq[BLACK] < 0) return false;

        side = "b".equals(parts[1]) ? BLACK : WHITE;

        castling = 0;
        String cStr = parts[2];
        if (cStr.indexOf('K') >= 0) castling |= 1;
        if (cStr.indexOf('Q') >= 0) castling |= 2;
        if (cStr.indexOf('k') >= 0) castling |= 4;
        if (cStr.indexOf('q') >= 0) castling |= 8;

        epSq = -1;
        String epStr = parts[3];
        if (!"-".equals(epStr) && epStr.length() == 2) {
            int col = epStr.charAt(0) - 'a';
            int row = '8' - epStr.charAt(1);
            if (row >= 0 && row < 8 && col >= 0 && col < 8) {
                epSq = row * 8 + col;
            }
        }

        hash = computeHash();
        return true;
    }

    private long computeHash() {
        long h = 0L;
        for (int sq = 0; sq < 64; sq++) {
            int p = board[sq];
            if (p != EMPTY) h ^= Z_PIECE[p][sq];
        }
        if (side == BLACK) h ^= Z_SIDE;
        h ^= Z_CASTLE[castling];
        h ^= Z_EP[epSq >= 0 ? epSq : 64];
        return h;
    }

    private boolean isSquareAttacked(int sq, int bySide) {
        int r = sq >> 3;
        int c = sq & 7;
        int colorBit = bySide << 3;

        // Pawns
        int pr = (bySide == WHITE) ? (r + 1) : (r - 1);
        int pawnPiece = colorBit | PAWN;
        if (pr >= 0 && pr < 8) {
            if (c > 0 && board[(pr << 3) | (c - 1)] == pawnPiece) return true;
            if (c < 7 && board[(pr << 3) | (c + 1)] == pawnPiece) return true;
        }

        // Knights
        int knightPiece = colorBit | KNIGHT;
        for (int i = 0; i < 8; i++) {
            int nr = r + KNIGHT_OFF[i][0];
            int nc = c + KNIGHT_OFF[i][1];
            if ((nr & ~7) == 0 && (nc & ~7) == 0 && board[(nr << 3) | nc] == knightPiece) return true;
        }

        // King
        int kingPiece = colorBit | KING;
        for (int i = 0; i < 8; i++) {
            int nr = r + KING_OFF[i][0];
            int nc = c + KING_OFF[i][1];
            if ((nr & ~7) == 0 && (nc & ~7) == 0 && board[(nr << 3) | nc] == kingPiece) return true;
        }

        // Orthogonal sliders (Rook / Queen)
        int rookPiece = colorBit | ROOK;
        int queenPiece = colorBit | QUEEN;
        for (int d = 0; d < 4; d++) {
            int dr = ROOK_DIR[d][0], dc = ROOK_DIR[d][1];
            int nr = r + dr, nc = c + dc;
            while ((nr & ~7) == 0 && (nc & ~7) == 0) {
                int p = board[(nr << 3) | nc];
                if (p != EMPTY) {
                    if (p == rookPiece || p == queenPiece) return true;
                    break;
                }
                nr += dr;
                nc += dc;
            }
        }

        // Diagonal sliders (Bishop / Queen)
        int bishopPiece = colorBit | BISHOP;
        for (int d = 0; d < 4; d++) {
            int dr = BISHOP_DIR[d][0], dc = BISHOP_DIR[d][1];
            int nr = r + dr, nc = c + dc;
            while ((nr & ~7) == 0 && (nc & ~7) == 0) {
                int p = board[(nr << 3) | nc];
                if (p != EMPTY) {
                    if (p == bishopPiece || p == queenPiece) return true;
                    break;
                }
                nr += dr;
                nc += dc;
            }
        }
        return false;
    }

    private boolean inCheck(int s) {
        return isSquareAttacked(kingSq[s], s ^ 1);
    }

    private int generateMoves(int[] list, boolean capturesOnly) {
        int count = 0;
        int myColorBit = side << 3;
        int oppColorBit = (side ^ 1) << 3;

        for (int sq = 0; sq < 64; sq++) {
            int p = board[sq];
            if (p == EMPTY || (p & 8) != myColorBit) continue;
            int type = p & 7;
            int r = sq >> 3;
            int c = sq & 7;

            if (type == PAWN) {
                int dir = (side == WHITE) ? -1 : 1;
                int startRow = (side == WHITE) ? 6 : 1;
                int promoRow = (side == WHITE) ? 0 : 7;
                int nr = r + dir;
                if ((nr & ~7) != 0) continue;

                // Forward pushes
                int fwdSq = (nr << 3) | c;
                if (board[fwdSq] == EMPTY) {
                    if (nr == promoRow) {
                        list[count++] = packMove(sq, fwdSq, QUEEN, FLAG_NONE);
                        if (!capturesOnly) {
                            list[count++] = packMove(sq, fwdSq, KNIGHT, FLAG_NONE);
                            list[count++] = packMove(sq, fwdSq, ROOK, FLAG_NONE);
                            list[count++] = packMove(sq, fwdSq, BISHOP, FLAG_NONE);
                        }
                    } else if (!capturesOnly) {
                        list[count++] = packMove(sq, fwdSq, 0, FLAG_NONE);
                        if (r == startRow) {
                            int dblSq = ((r + dir * 2) << 3) | c;
                            if (board[dblSq] == EMPTY) {
                                list[count++] = packMove(sq, dblSq, 0, FLAG_DOUBLE_PAWN);
                            }
                        }
                    }
                }

                // Captures & En Passant
                for (int dc = -1; dc <= 1; dc += 2) {
                    int nc = c + dc;
                    if ((nc & ~7) != 0) continue;
                    int capSq = (nr << 3) | nc;
                    int target = board[capSq];
                    if (target != EMPTY && (target & 8) == oppColorBit && (target & 7) != KING) {
                        if (nr == promoRow) {
                            list[count++] = packMove(sq, capSq, QUEEN, FLAG_NONE);
                            list[count++] = packMove(sq, capSq, KNIGHT, FLAG_NONE);
                            list[count++] = packMove(sq, capSq, ROOK, FLAG_NONE);
                            list[count++] = packMove(sq, capSq, BISHOP, FLAG_NONE);
                        } else {
                            list[count++] = packMove(sq, capSq, 0, FLAG_NONE);
                        }
                    } else if (capSq == epSq) {
                        list[count++] = packMove(sq, capSq, 0, FLAG_EP);
                    }
                }
            } else if (type == KNIGHT) {
                for (int i = 0; i < 8; i++) {
                    int nr = r + KNIGHT_OFF[i][0];
                    int nc = c + KNIGHT_OFF[i][1];
                    if ((nr & ~7) != 0 || (nc & ~7) != 0) continue;
                    int to = (nr << 3) | nc;
                    int target = board[to];
                    if (target == EMPTY) {
                        if (!capturesOnly) list[count++] = packMove(sq, to, 0, FLAG_NONE);
                    } else if ((target & 8) == oppColorBit && (target & 7) != KING) {
                        list[count++] = packMove(sq, to, 0, FLAG_NONE);
                    }
                }
            } else if (type == KING) {
                for (int i = 0; i < 8; i++) {
                    int nr = r + KING_OFF[i][0];
                    int nc = c + KING_OFF[i][1];
                    if ((nr & ~7) != 0 || (nc & ~7) != 0) continue;
                    int to = (nr << 3) | nc;
                    int target = board[to];
                    if (target == EMPTY) {
                        if (!capturesOnly) list[count++] = packMove(sq, to, 0, FLAG_NONE);
                    } else if ((target & 8) == oppColorBit && (target & 7) != KING) {
                        list[count++] = packMove(sq, to, 0, FLAG_NONE);
                    }
                }
                if (!capturesOnly) {
                    if (side == WHITE && sq == 60) {
                        if ((castling & 1) != 0 && board[63] == ROOK && board[61] == EMPTY && board[62] == EMPTY
                                && !isSquareAttacked(60, BLACK) && !isSquareAttacked(61, BLACK) && !isSquareAttacked(62, BLACK)) {
                            list[count++] = packMove(60, 62, 0, FLAG_CASTLE);
                        }
                        if ((castling & 2) != 0 && board[56] == ROOK && board[59] == EMPTY && board[58] == EMPTY && board[57] == EMPTY
                                && !isSquareAttacked(60, BLACK) && !isSquareAttacked(59, BLACK) && !isSquareAttacked(58, BLACK)) {
                            list[count++] = packMove(60, 58, 0, FLAG_CASTLE);
                        }
                    } else if (side == BLACK && sq == 4) {
                        if ((castling & 4) != 0 && board[7] == (8 | ROOK) && board[5] == EMPTY && board[6] == EMPTY
                                && !isSquareAttacked(4, WHITE) && !isSquareAttacked(5, WHITE) && !isSquareAttacked(6, WHITE)) {
                            list[count++] = packMove(4, 6, 0, FLAG_CASTLE);
                        }
                        if ((castling & 8) != 0 && board[0] == (8 | ROOK) && board[3] == EMPTY && board[2] == EMPTY && board[1] == EMPTY
                                && !isSquareAttacked(4, WHITE) && !isSquareAttacked(3, WHITE) && !isSquareAttacked(2, WHITE)) {
                            list[count++] = packMove(4, 2, 0, FLAG_CASTLE);
                        }
                    }
                }
            } else {
                // Sliders (Bishop, Rook, Queen)
                if (type == ROOK || type == QUEEN) {
                    for (int d = 0; d < 4; d++) {
                        int dr = ROOK_DIR[d][0], dc = ROOK_DIR[d][1];
                        int nr = r + dr, nc = c + dc;
                        while ((nr & ~7) == 0 && (nc & ~7) == 0) {
                            int to = (nr << 3) | nc;
                            int target = board[to];
                            if (target == EMPTY) {
                                if (!capturesOnly) list[count++] = packMove(sq, to, 0, FLAG_NONE);
                            } else {
                                if ((target & 8) == oppColorBit && (target & 7) != KING) {
                                    list[count++] = packMove(sq, to, 0, FLAG_NONE);
                                }
                                break;
                            }
                            nr += dr;
                            nc += dc;
                        }
                    }
                }
                if (type == BISHOP || type == QUEEN) {
                    for (int d = 0; d < 4; d++) {
                        int dr = BISHOP_DIR[d][0], dc = BISHOP_DIR[d][1];
                        int nr = r + dr, nc = c + dc;
                        while ((nr & ~7) == 0 && (nc & ~7) == 0) {
                            int to = (nr << 3) | nc;
                            int target = board[to];
                            if (target == EMPTY) {
                                if (!capturesOnly) list[count++] = packMove(sq, to, 0, FLAG_NONE);
                            } else {
                                if ((target & 8) == oppColorBit && (target & 7) != KING) {
                                    list[count++] = packMove(sq, to, 0, FLAG_NONE);
                                }
                                break;
                            }
                            nr += dr;
                            nc += dc;
                        }
                    }
                }
            }
        }
        return count;
    }

    private boolean makeMove(int m) {
        int from = moveFrom(m);
        int to = moveTo(m);
        int promo = movePromo(m);
        int flag = moveFlag(m);

        int moving = board[from];
        int cap = board[to];

        uCap[plyIdx] = cap;
        uCastle[plyIdx] = castling;
        uEp[plyIdx] = epSq;
        uHash[plyIdx] = hash;
        plyIdx++;

        // Update hash: remove old ep & castling & moving piece
        hash ^= Z_EP[epSq >= 0 ? epSq : 64];
        hash ^= Z_CASTLE[castling];
        hash ^= Z_PIECE[moving][from];
        if (cap != EMPTY) hash ^= Z_PIECE[cap][to];

        board[from] = EMPTY;
        int placed = (promo != 0) ? ((side << 3) | promo) : moving;
        board[to] = placed;
        hash ^= Z_PIECE[placed][to];

        if ((moving & 7) == KING) {
            kingSq[side] = to;
        }

        if (flag == FLAG_EP) {
            int capSq = (side == WHITE) ? (to + 8) : (to - 8);
            int epPawn = board[capSq];
            hash ^= Z_PIECE[epPawn][capSq];
            board[capSq] = EMPTY;
        } else if (flag == FLAG_CASTLE) {
            int rFrom, rTo;
            if (to == 62) { rFrom = 63; rTo = 61; }
            else if (to == 58) { rFrom = 56; rTo = 59; }
            else if (to == 6) { rFrom = 7; rTo = 5; }
            else { rFrom = 0; rTo = 3; }
            int rook = board[rFrom];
            board[rFrom] = EMPTY;
            board[rTo] = rook;
            hash ^= Z_PIECE[rook][rFrom];
            hash ^= Z_PIECE[rook][rTo];
        }

        epSq = (flag == FLAG_DOUBLE_PAWN) ? ((from + to) >> 1) : -1;
        castling &= CASTLE_MASK[from] & CASTLE_MASK[to];

        hash ^= Z_CASTLE[castling];
        hash ^= Z_EP[epSq >= 0 ? epSq : 64];
        hash ^= Z_SIDE;

        int moverSide = side;
        side ^= 1;

        if (inCheck(moverSide)) {
            unmakeMove(m);
            return false;
        }
        return true;
    }

    private void unmakeMove(int m) {
        side ^= 1;
        plyIdx--;

        int from = moveFrom(m);
        int to = moveTo(m);
        int promo = movePromo(m);
        int flag = moveFlag(m);

        int moved = (promo != 0) ? ((side << 3) | PAWN) : board[to];
        board[from] = moved;
        board[to] = uCap[plyIdx];

        if ((moved & 7) == KING) {
            kingSq[side] = from;
        }

        if (flag == FLAG_EP) {
            int capSq = (side == WHITE) ? (to + 8) : (to - 8);
            board[capSq] = ((side ^ 1) << 3) | PAWN;
        } else if (flag == FLAG_CASTLE) {
            int rFrom, rTo;
            if (to == 62) { rFrom = 63; rTo = 61; }
            else if (to == 58) { rFrom = 56; rTo = 59; }
            else if (to == 6) { rFrom = 7; rTo = 5; }
            else { rFrom = 0; rTo = 3; }
            board[rFrom] = board[rTo];
            board[rTo] = EMPTY;
        }

        castling = uCastle[plyIdx];
        epSq = uEp[plyIdx];
        hash = uHash[plyIdx];
    }

    private int evaluate() {
        int mgW = 0, egW = 0, mgB = 0, egB = 0;
        int phase = 0;
        int wBishops = 0, bBishops = 0;
        int wNonPawn = 0, bNonPawn = 0;

        // Track topmost Black pawn (min row) and bottommost White pawn (max row) per file
        int[] bPawnMinRow = {8, 8, 8, 8, 8, 8, 8, 8};
        int[] wPawnMaxRow = {-1, -1, -1, -1, -1, -1, -1, -1};

        for (int sq = 0; sq < 64; sq++) {
            int p = board[sq];
            if (p == EMPTY) continue;
            int type = p & 7;
            boolean isW = (p & 8) == 0;
            phase += PHASE_INC[type];

            if (isW) {
                mgW += MG_VAL[type] + MG_PST[type][sq];
                egW += EG_VAL[type] + EG_PST[type][sq];
                if (type == BISHOP) wBishops++;
                if (type != PAWN && type != KING) wNonPawn += MG_VAL[type];
                if (type == PAWN) {
                    int r = sq >> 3, c = sq & 7;
                    if (r > wPawnMaxRow[c]) wPawnMaxRow[c] = r;
                }
            } else {
                int flipSq = sq ^ 56;
                mgB += MG_VAL[type] + MG_PST[type][flipSq];
                egB += EG_VAL[type] + EG_PST[type][flipSq];
                if (type == BISHOP) bBishops++;
                if (type != PAWN && type != KING) bNonPawn += MG_VAL[type];
                if (type == PAWN) {
                    int r = sq >> 3, c = sq & 7;
                    if (r < bPawnMinRow[c]) bPawnMinRow[c] = r;
                }
            }
        }

        // Bishop pair bonus
        if (wBishops >= 2) { mgW += 30; egW += 48; }
        if (bBishops >= 2) { mgB += 30; egB += 48; }

        // Passed pawn bonus
        final int[] passEg = {0, 12, 22, 38, 65, 110, 175, 0};
        for (int sq = 0; sq < 64; sq++) {
            int p = board[sq];
            if ((p & 7) != PAWN) continue;
            int r = sq >> 3, c = sq & 7;
            if ((p & 8) == 0) {
                boolean passed = true;
                for (int dc = -1; dc <= 1; dc++) {
                    int nc = c + dc;
                    if (nc >= 0 && nc < 8 && bPawnMinRow[nc] < r) {
                        passed = false;
                        break;
                    }
                }
                if (passed) {
                    int adv = 7 - r;
                    mgW += passEg[adv] >> 1;
                    egW += passEg[adv];
                }
            } else {
                boolean passed = true;
                for (int dc = -1; dc <= 1; dc++) {
                    int nc = c + dc;
                    if (nc >= 0 && nc < 8 && wPawnMaxRow[nc] > r) {
                        passed = false;
                        break;
                    }
                }
                if (passed) {
                    int adv = r;
                    mgB += passEg[adv] >> 1;
                    egB += passEg[adv];
                }
            }
        }

        // Endgame mop-up evaluation (forces checkmate when winning cleanly)
        if (phase <= 6) {
            int wkR = kingSq[WHITE] >> 3, wkC = kingSq[WHITE] & 7;
            int bkR = kingSq[BLACK] >> 3, bkC = kingSq[BLACK] & 7;
            int kingDist = Math.abs(wkR - bkR) + Math.abs(wkC - bkC);
            if (egW > egB + 250 && wNonPawn > 0) {
                int bkEdge = Math.max(3 - bkR, bkR - 4) + Math.max(3 - bkC, bkC - 4);
                egW += bkEdge * 14 + (14 - kingDist) * 7;
            } else if (egB > egW + 250 && bNonPawn > 0) {
                int wkEdge = Math.max(3 - wkR, wkR - 4) + Math.max(3 - wkC, wkC - 4);
                egB += wkEdge * 14 + (14 - kingDist) * 7;
            }
        }

        int mgScore = mgW - mgB;
        int egScore = egW - egB;
        int mgPhase = Math.min(24, phase);
        int egPhase = 24 - mgPhase;
        int eval = (mgScore * mgPhase + egScore * egPhase) / 24;
        return (side == WHITE) ? eval : -eval;
    }

    private boolean hasNonPawnMaterial(int s) {
        int colorBit = s << 3;
        for (int sq = 0; sq < 64; sq++) {
            int p = board[sq];
            if (p != EMPTY && (p & 8) == colorBit) {
                int t = p & 7;
                if (t != PAWN && t != KING) return true;
            }
        }
        return false;
    }

    private void checkTime() {
        if ((nodes & 2047L) == 0L) {
            if (System.currentTimeMillis() >= deadlineMs || (externalStop != null && externalStop.get())) {
                timeUp = true;
            }
        }
    }

    private void scoreMoves(int[] moves, int[] scores, int count, int ply, int ttBest) {
        for (int i = 0; i < count; i++) {
            int m = moves[i];
            if (m == ttBest) {
                scores[i] = 2000000;
            } else {
                int to = moveTo(m);
                int from = moveFrom(m);
                int cap = board[to];
                int promo = movePromo(m);
                if (cap != EMPTY || moveFlag(m) == FLAG_EP) {
                    int victim = (cap != EMPTY) ? MVV_VAL[cap & 7] : 100;
                    int attacker = MVV_VAL[board[from] & 7];
                    scores[i] = 1000000 + victim * 10 - attacker;
                } else if (promo != 0) {
                    scores[i] = 900000 + MVV_VAL[promo];
                } else if (m == killers[ply][0]) {
                    scores[i] = 800000;
                } else if (m == killers[ply][1]) {
                    scores[i] = 750000;
                } else {
                    scores[i] = history[side][from][to];
                }
            }
        }
    }

    private static void pickBestMove(int[] moves, int[] scores, int start, int count) {
        int bestIdx = start;
        int bestSc = scores[start];
        for (int i = start + 1; i < count; i++) {
            if (scores[i] > bestSc) {
                bestSc = scores[i];
                bestIdx = i;
            }
        }
        if (bestIdx != start) {
            int tm = moves[start]; moves[start] = moves[bestIdx]; moves[bestIdx] = tm;
            int ts = scores[start]; scores[start] = scores[bestIdx]; scores[bestIdx] = ts;
        }
    }

    private int quiesce(int alpha, int beta, int ply) {
        nodes++;
        checkTime();
        if (timeUp) return 0;

        boolean check = inCheck(side);
        if (!check) {
            int standPat = evaluate();
            if (standPat >= beta) return beta;
            if (standPat > alpha) alpha = standPat;
            if (ply >= MAX_PLY) return standPat;
        } else if (ply >= MAX_PLY) {
            return evaluate();
        }

        int[] moves = moveBuf[ply];
        int[] scores = scoreBuf[ply];
        // When in check, search ALL legal evasions, not just captures!
        int count = generateMoves(moves, !check);
        scoreMoves(moves, scores, count, ply, 0);

        int legalMoves = 0;
        for (int i = 0; i < count; i++) {
            pickBestMove(moves, scores, i, count);
            int m = moves[i];
            if (!makeMove(m)) continue;
            legalMoves++;

            int score = -quiesce(-beta, -alpha, ply + 1);
            unmakeMove(m);

            if (timeUp) return 0;
            if (score >= beta) return beta;
            if (score > alpha) alpha = score;
        }

        if (check && legalMoves == 0) {
            return -MATE + ply;
        }
        return alpha;
    }

    private int negamax(int depth, int alpha, int beta, int ply, boolean allowNull) {
        nodes++;
        checkTime();
        if (timeUp) return 0;

        // Repetition check along current search branch
        if (ply > 0) {
            for (int i = plyIdx - 2; i >= 0; i -= 2) {
                if (uHash[i] == hash) return 0;
            }
        }

        boolean inChk = inCheck(side);
        if (inChk && ply < MAX_PLY - 2) {
            depth++; // Check extension
        }

        if (depth <= 0 || ply >= MAX_PLY) {
            return quiesce(alpha, beta, ply);
        }

        int origAlpha = alpha;
        int ttIdx = (int) (hash & TT_MASK);
        int ttBest = 0;
        if (ttKey[ttIdx] == hash) {
            ttBest = ttMove[ttIdx];
            if (ply > 0 && ttDepth[ttIdx] >= depth) {
                int sc = ttScore[ttIdx];
                if (sc > MATE - 100) sc -= ply;
                else if (sc < -MATE + 100) sc += ply;
                byte fl = ttFlag[ttIdx];
                if (fl == TT_EXACT) return sc;
                if (fl == TT_ALPHA && sc <= alpha) return alpha;
                if (fl == TT_BETA && sc >= beta) return beta;
            }
        }

        // Null-Move Pruning
        if (allowNull && !inChk && depth >= 3 && ply > 0 && hasNonPawnMaterial(side)) {
            int savedEp = epSq;
            long savedHash = hash;
            hash ^= Z_EP[epSq >= 0 ? epSq : 64];
            epSq = -1;
            hash ^= Z_EP[64];
            hash ^= Z_SIDE;
            side ^= 1;

            int r = (depth >= 6) ? 3 : 2;
            int nullScore = -negamax(depth - 1 - r, -beta, -beta + 1, ply + 1, false);

            side ^= 1;
            epSq = savedEp;
            hash = savedHash;

            if (timeUp) return 0;
            if (nullScore >= beta && nullScore < MATE - 100) {
                return beta;
            }
        }

        int[] moves = moveBuf[ply];
        int[] scores = scoreBuf[ply];
        int count = generateMoves(moves, false);
        scoreMoves(moves, scores, count, ply, ttBest);

        int bestMove = 0;
        int bestScore = -INF;
        int legalMoves = 0;

        for (int i = 0; i < count; i++) {
            pickBestMove(moves, scores, i, count);
            int m = moves[i];
            boolean isTactical = (board[moveTo(m)] != EMPTY) || movePromo(m) != 0 || moveFlag(m) == FLAG_EP;

            if (!makeMove(m)) continue;
            legalMoves++;

            int score;
            if (legalMoves == 1) {
                score = -negamax(depth - 1, -beta, -alpha, ply + 1, true);
            } else {
                int reduction = 0;
                if (depth >= 3 && legalMoves >= 4 && !inChk && !isTactical && !inCheck(side)) {
                    reduction = (legalMoves >= 10 && depth >= 5) ? 2 : 1;
                }
                score = -negamax(depth - 1 - reduction, -alpha - 1, -alpha, ply + 1, true);
                if (score > alpha && reduction > 0) {
                    score = -negamax(depth - 1, -alpha - 1, -alpha, ply + 1, true);
                }
                if (score > alpha && score < beta) {
                    score = -negamax(depth - 1, -beta, -alpha, ply + 1, true);
                }
            }

            unmakeMove(m);
            if (timeUp) return 0;

            if (score > bestScore) {
                bestScore = score;
                bestMove = m;
                if (score > alpha) {
                    alpha = score;
                    if (alpha >= beta) {
                        if (!isTactical) {
                            killers[ply][1] = killers[ply][0];
                            killers[ply][0] = m;
                            history[side][moveFrom(m)][moveTo(m)] += depth * depth;
                            if (history[side][moveFrom(m)][moveTo(m)] > 600000) {
                                for (int a = 0; a < 64; a++) {
                                    for (int b = 0; b < 64; b++) {
                                        history[side][a][b] >>>= 1;
                                    }
                                }
                            }
                        }
                        break;
                    }
                }
            }
        }

        if (legalMoves == 0) {
            return inChk ? (-MATE + ply) : 0;
        }

        // Store in Transposition Table
        int storeScore = bestScore;
        if (storeScore > MATE - 100) storeScore += ply;
        else if (storeScore < -MATE + 100) storeScore -= ply;

        ttKey[ttIdx] = hash;
        ttMove[ttIdx] = bestMove;
        ttScore[ttIdx] = (short) Math.max(-32000, Math.min(32000, storeScore));
        ttDepth[ttIdx] = (byte) Math.min(120, depth);
        if (bestScore <= origAlpha) ttFlag[ttIdx] = TT_ALPHA;
        else if (bestScore >= beta) ttFlag[ttIdx] = TT_BETA;
        else ttFlag[ttIdx] = TT_EXACT;

        return bestScore;
    }

    public SearchResult findBestMove(String fen, long timeLimitMs, AtomicBoolean stopFlag) {
        if (!loadFen(fen)) return null;

        this.externalStop = stopFlag;
        this.deadlineMs = System.currentTimeMillis() + timeLimitMs;
        this.nodes = 0;
        this.timeUp = false;

        for (int p = 0; p < MAX_PLY + 8; p++) {
            killers[p][0] = 0;
            killers[p][1] = 0;
        }
        for (int s = 0; s < 2; s++) {
            for (int a = 0; a < 64; a++) {
                Arrays.fill(history[s][a], 0);
            }
        }

        int[] rootMoves = moveBuf[0];
        int[] rootScores = scoreBuf[0];
        int count = generateMoves(rootMoves, false);

        // Filter to strictly legal root moves first
        int legalCount = 0;
        for (int i = 0; i < count; i++) {
            int m = rootMoves[i];
            if (makeMove(m)) {
                unmakeMove(m);
                rootMoves[legalCount++] = m;
            }
        }
        if (legalCount == 0) return null;
        if (legalCount == 1) {
            return new SearchResult(moveToUci(rootMoves[0]), 1, 0);
        }

        int bestMoveOverall = rootMoves[0];
        int bestScoreOverall = 0;
        int completedDepth = 1;

        for (int depth = 1; depth <= 18; depth++) {
            scoreMoves(rootMoves, rootScores, legalCount, 0, bestMoveOverall);
            int alpha = -INF;
            int beta = INF;
            int iterBestMove = bestMoveOverall;
            int iterBestScore = -INF;

            for (int i = 0; i < legalCount; i++) {
                pickBestMove(rootMoves, rootScores, i, legalCount);
                int m = rootMoves[i];
                makeMove(m);
                int sc;
                if (i == 0) {
                    sc = -negamax(depth - 1, -beta, -alpha, 1, true);
                } else {
                    sc = -negamax(depth - 1, -alpha - 1, -alpha, 1, true);
                    if (sc > alpha && !timeUp) {
                        sc = -negamax(depth - 1, -beta, -alpha, 1, true);
                    }
                }
                unmakeMove(m);

                if (timeUp) break;
                if (sc > iterBestScore) {
                    iterBestScore = sc;
                    iterBestMove = m;
                    if (sc > alpha) alpha = sc;
                }
            }

            if (timeUp) {
                if (iterBestMove != 0 && iterBestScore > bestScoreOverall && depth >= 2) {
                    bestMoveOverall = iterBestMove;
                    bestScoreOverall = iterBestScore;
                }
                break;
            }

            bestMoveOverall = iterBestMove;
            bestScoreOverall = iterBestScore;
            completedDepth = depth;

            // Forced mate found -> no need to waste extra time
            if (bestScoreOverall > MATE - 100) break;
            if (System.currentTimeMillis() + 60 >= deadlineMs) break;
        }

        return new SearchResult(moveToUci(bestMoveOverall), completedDepth, bestScoreOverall);
    }
}
