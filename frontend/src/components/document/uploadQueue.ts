/**
 * 上传队列的共享词汇。
 *
 * <p>队列项的类型、字节格式化与状态文案放在这里，而不是各自的组件里：
 * `UploadProgressItem` 负责画一行、上传页负责排队与重试，两处都要用同一套状态名与文案。
 * 各写一份的结果是"上传中"在进度条上是三个字、在汇总里变成 40% —— 同一条上传两个说法。
 */

export type UploadStatus = 'pending' | 'uploading' | 'done' | 'failed'

/** 队列里的一项 = 学生选中的一个文件。上传以文件为单位，进度与重试也是。 */
export interface UploadQueueItem {
  /** 客户端自增序号：同一个文件可以被选两次，文件名不能当键。 */
  id: number
  file: File
  status: UploadStatus
  /** 上传字节百分比，只在 `uploading` 时有意义。 */
  percent: number
  error?: string
}

/** 人可读的文件大小。小于 1 MB 时显示整数 KB，免得"0.0 MB"看着像空文件。 */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

/**
 * 队列项的状态文案。
 *
 * <p>上传到 100% 之后服务端还要落库、渲染 PDF 页，这段等待没有可上报的阶段，
 * 所以 100% 时改说"服务器正在处理" —— 一直显示"上传中 100%"看起来就是卡住了。
 */
export function uploadStatusLabel(item: UploadQueueItem): string {
  if (item.status === 'uploading') {
    return item.percent >= 100 ? '服务器正在处理…' : `上传中 ${item.percent}%`
  }
  if (item.status === 'done') return '已上传'
  if (item.status === 'failed') return item.error ?? '上传失败'
  return '等待上传'
}
