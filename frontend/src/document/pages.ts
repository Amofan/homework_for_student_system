/**
 * 页面在界面上最小的样子。
 *
 * <p>与 `regions.ts` 里的 `DrawableRegion` 同一个理由：整卷页（`PaperPage`）与答卷页
 * （`AnswerPage`）是两个类型 —— 页 id 一个叫 `pageId` 一个叫 `submissionPageId`，
 * 答卷页也没有 `thumbnailFileId`。但缩略图条真正用到的只有下面这四件事。
 *
 * <p>共用一份"画一页缩略图需要的形状"，好过让调用方为了凑类型去伪造一堆用不到的字段
 * （那样的伪造字段会被读成"真的有这个值"），也好过在组件里写
 * `page.pageId ?? page.submissionPageId` —— 那种写法看起来像还有第三种情况。
 *
 * <p>`pageId` 是**回传用**的标识：缩略图条点击时把它原样抛回去，
 * 由调用方决定它对应自己那边的哪个 id。
 */
export interface ThumbnailPage {
  pageId: number
  pageNo: number
  /** 整页图。答卷页没有单独的缩略图，就用它自己。 */
  pageFileId: number
  thumbnailFileId?: number
  /** 只用到长度（"这一页上有没有识别到东西"），所以不约束元素类型。 */
  regions: readonly unknown[]
}
