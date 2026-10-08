export type ActivityDay = {
  date: string;
  conversations: number;
  schedules: number;
  todos: number;
  employees: number;
};

export type ActivityTrend = {
  startDate: string;
  endDate: string;
  timezone: string;
  days: ActivityDay[];
};
