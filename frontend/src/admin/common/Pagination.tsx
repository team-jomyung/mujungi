import { ADMIN_COPY as C } from "./copy";
import { pageLabel, totalRecords } from "./format";

interface PaginationProps {
  page: number;
  pageSize: number;
  total: number;
  onPage: (page: number) => void;
}

export function Pagination({ page, pageSize, total, onPage }: PaginationProps) {
  const pages = Math.max(1, Math.ceil(total / pageSize));
  return (
    <nav className="mj-admin-pagination" aria-label={C.pagination}>
      <span>{totalRecords(total)}</span>
      <div>
        <button type="button" disabled={page <= 0} onClick={() => onPage(page - 1)}>{C.previous}</button>
        <span aria-live="polite">{pageLabel(page, pages)}</span>
        <button type="button" disabled={page + 1 >= pages} onClick={() => onPage(page + 1)}>{C.next}</button>
      </div>
    </nav>
  );
}
