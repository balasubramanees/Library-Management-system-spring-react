/**
 * HTTP contract of the gateway, mirrored from the Spring services' DTOs
 * (user-service SessionDtos, library-service LibraryDtos). Changing a type here
 * is an API change and must be made on both sides.
 */

export type BookStatus = 'available' | 'borrowed';

export interface UserDto {
  id: string;
  name: string;
  isAdmin: boolean;
}

export interface LoginRequest {
  name: string;
}

export interface LoginResponse {
  token: string;
  user: UserDto;
  /** True when this login registered the user. */
  created: boolean;
  /** "Hello, <name>!" and, for admin, the management notice. */
  messages: string[];
}

export interface MessageResponse {
  message: string;
}

/** ISO calendar date, e.g. "2026-12-08". */
export type IsoDate = string;

export interface BookDto {
  id: string;
  title: string;
  status: BookStatus;
  /** True when the caller holds the book. */
  borrowedByMe: boolean;
  /** Caller's 1-based wait list position, or null when not waiting. */
  myWaitlistPosition: number | null;
  waitlistLength: number;
  /** Only sent to the admin (librarian). */
  borrowerName: string | null;
  /** Only sent to the borrower and the admin. */
  dueDate: IsoDate | null;
  /** Only sent to the borrower and the admin; 0 when on time. */
  overdueDays: number | null;
}

export interface BookListResponse {
  books: BookDto[];
  /** "No books are registered" when the catalogue is empty. */
  message: string | null;
}

export interface TitleRequest {
  title: string;
}

export interface AddBookResponse extends MessageResponse {
  book: BookDto;
}

export interface WaitlistResponse extends MessageResponse {
  position: number;
}

export interface BorrowResponse extends MessageResponse {
  dueDate: IsoDate;
}

export interface ReturnResponse extends MessageResponse {
  /** Rupees deducted from the deposit (0 when returned on time). */
  lateFee: number;
  balance: number | null;
}

export interface ExtendResponse extends MessageResponse {
  dueDate: IsoDate;
  extensionsLeft: number;
}

export interface DepositRequest {
  /** Whole rupees. */
  amount: number;
}

export interface DepositResponse extends MessageResponse {
  balance: number;
}

export interface LoanDto {
  bookId: string;
  title: string;
  borrowedOn: IsoDate;
  dueDate: IsoDate;
  extensionsUsed: number;
  extensionsLeft: number;
  /** 0 when not overdue. */
  overdueDays: number;
  /** Late fee if returned today (rupees). */
  lateFeeSoFar: number;
}

export interface WaitlistEntryDto {
  bookId: string;
  title: string;
  position: number;
}

export interface LedgerEntryDto {
  date: IsoDate;
  description: string;
  /** Rupees; negative for charges. */
  amount: number;
}

export interface AccountDto {
  /** Rupees; may be negative when late fees exceed the deposit. */
  balance: number;
  /** True while the member has an overdue book or a balance of zero or less. */
  blocked: boolean;
  blockedReason: string | null;
  ledger: LedgerEntryDto[];
}

export interface PolicyDto {
  loanPeriodDays: number;
  extensionDays: number;
  maxExtensions: number;
  lateFeePerDay: number;
  initialDeposit: number;
  minimumTopUp: number;
}

export interface NotificationDto {
  id: string;
  type: 'WAITLIST_ALLOCATED' | 'DEPOSIT_RECEIVED';
  bookId: string | null;
  title: string | null;
  message: string;
  createdAt: string;
}

export interface StatusResponse {
  /** The library's current date (may be moved forward by the admin's demo clock). */
  today: IsoDate;
  borrowed: LoanDto[];
  waitlists: WaitlistEntryDto[];
  /** Pending notifications; delivered once, then cleared server-side. */
  notifications: NotificationDto[];
  /** Null for the admin (librarian), who has no deposit account. */
  account: AccountDto | null;
  policy: PolicyDto;
  /** The `status` output as the CLI would print it. */
  lines: string[];
}

export type LoanState = 'ON_TIME' | 'DUE_SOON' | 'OVERDUE';

/** A current loan as the admin sees it (GET /admin/dashboard). */
export interface AdminLoanDto {
  bookId: string;
  title: string;
  memberId: string;
  memberName: string;
  borrowedOn: IsoDate;
  dueDate: IsoDate;
  /** Negative when overdue, 0 when due today. */
  daysUntilDue: number;
  state: LoanState;
  extensionsUsed: number;
  waitlistLength: number;
  lateFeeSoFar: number;
}

export interface AdminMemberDto {
  id: string;
  name: string;
  booksBorrowed: number;
  overdue: number;
  dueSoon: number;
  waitlists: number;
  balance: number;
  /** Null when the member may borrow. */
  blockedReason: string | null;
}

export interface AdminSummaryDto {
  totalBooks: number;
  availableBooks: number;
  borrowedBooks: number;
  /** Registered members (a member registers on first login). */
  members: number;
  membersWithBooks: number;
  overdueLoans: number;
  dueSoonLoans: number;
  waitlistEntries: number;
  blockedMembers: number;
  /** Sum of every member's deposit balance, rupees. */
  depositsHeld: number;
  /** Late fees that would be charged if every overdue book came back today, rupees. */
  lateFeesOutstanding: number;
}

export interface AdminDashboardResponse {
  today: IsoDate;
  /** A loan due within this many days is DUE_SOON. */
  dueSoonDays: number;
  summary: AdminSummaryDto;
  /** Sorted by name. */
  members: AdminMemberDto[];
  /** Soonest due first. */
  loans: AdminLoanDto[];
}

/** Admin demo clock (only present when time travel is enabled). */
export interface ClockResponse {
  today: IsoDate;
  message: string;
}

export type ErrorCode =
  | 'VALIDATION_ERROR'
  | 'UNAUTHENTICATED'
  | 'FORBIDDEN'
  | 'BOOK_NOT_FOUND'
  | 'BOOK_ALREADY_EXISTS'
  | 'BOOK_NOT_AVAILABLE'
  | 'BOOK_AVAILABLE'
  | 'ALREADY_BORROWED'
  | 'NOT_BORROWED_BY_USER'
  | 'ALREADY_WAITLISTED'
  | 'BORROWING_BLOCKED'
  | 'EXTENSION_NOT_ALLOWED'
  | 'NOT_FOUND'
  | 'METHOD_NOT_ALLOWED'
  | 'UNSUPPORTED_MEDIA_TYPE'
  | 'RATE_LIMITED'
  | 'SERVICE_UNAVAILABLE'
  | 'INTERNAL_ERROR'
  /** Client-side only: network failure, timeout or open circuit. */
  | 'NETWORK_ERROR'
  | 'TIMEOUT'
  | 'CIRCUIT_OPEN';

export interface ErrorResponse {
  error: {
    code: ErrorCode;
    message: string;
    requestId: string;
  };
}

export const REQUEST_ID_HEADER = 'X-Request-Id';
