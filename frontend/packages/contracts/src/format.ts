/**
 * Presentation helpers shared by the shell and the remotes, so dates and money look the same
 * everywhere (and match the server's messages: "08 Dec 2026", "Rs. 500").
 */
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** "2026-12-08" -> "08 Dec 2026". */
export function formatDate(iso: string): string {
  const [year, month, day] = iso.split('-');
  return `${day} ${MONTHS[Number(month) - 1] ?? month} ${year}`;
}

/** 500 -> "Rs. 500", -20 -> "-Rs. 20". */
export function formatRupees(amount: number): string {
  return amount < 0 ? `-Rs. ${Math.abs(amount)}` : `Rs. ${amount}`;
}

export function plural(count: number, word: string): string {
  return `${count} ${word}${count === 1 ? '' : 's'}`;
}
