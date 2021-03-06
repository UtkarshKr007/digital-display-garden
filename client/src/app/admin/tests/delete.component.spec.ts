import {ComponentFixture, TestBed, async} from "@angular/core/testing";
import { Observable } from "rxjs";
import {FormsModule} from "@angular/forms";
import {DeleteComponent} from "../src/delete.component";
import {AdminService} from "../src/admin.service";
import {RouterTestingModule} from "@angular/router/testing";

describe("Delete Component", () => {

    let deleteComponent: DeleteComponent;
    let fixture: ComponentFixture<DeleteComponent>;
    let adminServiceStub: {
        getUploadIds: () => Observable<string[]>,
        getLiveUploadId: () => Observable<string>,
        deleteUploadId: (string) => Observable<any>,
        authorized: () => Observable<boolean>
    };

    beforeEach(() => {
        adminServiceStub = {
            getUploadIds: () => {
                return Observable.of(["upload id 1", "upload id 2"]);
            },
            getLiveUploadId: () => {
                return Observable.of("upload id 2");
            },
            deleteUploadId: (uploadID: string) => {

                return Observable.of({
                    success: ["upload id 1", "upload id 2"].filter(str => str !== uploadID).length === 1,
                    uploadIDs: ["upload id 1", "upload id 2"].filter(str => str !== uploadID)
                });
            },
            authorized: () => {
                return Observable.of(true);
            }
        };

        TestBed.configureTestingModule({
            imports: [FormsModule, RouterTestingModule ],
            declarations: [ DeleteComponent],
            providers:    [{provide: AdminService, useValue: adminServiceStub}]
        });

    });

    beforeEach(
        async(() => {
            TestBed.compileComponents().then(() => {
                fixture = TestBed.createComponent(DeleteComponent);
                deleteComponent = fixture.componentInstance;
                fixture.detectChanges();
            });
    }));

    it("can be initialized", () => {
        expect(deleteComponent).toBeDefined();
    });

    it("initializes the authorized field", () => {
        expect(deleteComponent.authorized).toEqual(true);
    });

    it("can delete an uploadID", () => {
       deleteComponent.delete("upload id 1");
       expect(deleteComponent.uploadIDs).toEqual(["upload id 2"]);
    });

    it("changes nothing on failed deletions", () => {
       deleteComponent.delete("blablabla");
       expect(deleteComponent.uploadIDs).toEqual(["upload id 1", "upload id 2"]);
    });

});