import { Toast } from "@halo-dev/components";
import axios, { type AxiosError } from "axios";

export const TEMPLATE_STORE_URL = "https://www.yunext.cn";

const storeApiClient = axios.create({
  baseURL: TEMPLATE_STORE_URL,
  paramsSerializer: {
    indexes: null,
  },
  timeout: 15_000,
});

storeApiClient.interceptors.response.use(
  (response) => response,
  (error: AxiosError<ProblemDetail>) => {
    if (/Network Error/i.test(error.message) || !error.response) {
      Toast.error("模板市场连接失败，请检查网络连接");
      return Promise.reject(error);
    }

    const { detail, title } = error.response.data ?? {};
    Toast.error(detail || title || "模板市场请求失败");
    return Promise.reject(error);
  },
);

export interface ProblemDetail {
  detail?: string;
  instance?: string;
  status?: number;
  title?: string;
  type?: string;
}

export default storeApiClient;
